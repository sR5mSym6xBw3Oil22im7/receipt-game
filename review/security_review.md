# セキュリティレビュー（receipt-game-main）

- レビュー日: 2026-10-11
- 対象: `reBack/src/main`・`reBack/src/test`・`reBack/pom.xml`・`Dockerfile`・`render.yml`・`.env.example`・`.gitignore`・`.dockerignore`、`reFront/`、ルートの `index.html`・`.gitignore`
- 対象外（読まず、根拠にもしていない）: `doc/` 全体、`README.md`（ルート・reBack・reFront）
- 方針: **`reBack/` と `reFront/` のファイルは変更していない**（`git status` は `review/` が未追跡なだけ）。PoC・テスト・ログはすべて `review/poc/` に置いた。PoC の実行は `reBack` のコピー（作業用ディレクトリ）で行った。実行方法は [`review/poc/RUN.md`](poc/RUN.md)。
- 確認したバージョン（`mvn dependency:tree` と `pom.xml`）: Spring Boot 4.1.0 / Spring Security 7.1.0 / Spring Framework 7.0.8 / Tomcat 11.0.22 / Jackson 3.1.4（`tools.jackson`）/ PostgreSQL JDBC 42.7.11 / google-genai 1.67.0 / gson 2.13.1。Spring の挙動（CSRF・認可・ヘッダー・エラー処理）は知識で判断せず、**実際にTomcatを起動したテスト**（`HttpPocTest`）で確かめた。

---

## 0. 修正の対応状況（2026-10-11 追記）

本文（第1章以降）は **修正前** のレビュー結果。その後、次のとおりコードを修正した。修正後の `mvn test` は **168件すべて成功**、`node --test "reFront/test/*.test.mjs"` は **23件すべて成功**（いずれも修正直後の当時の件数。現在の件数は [テスト結果報告書](../doc/26_test_result_report.html) を参照）。同じPoCを修正後のコードで再実行した結果は `review/poc/results_poc_after_fix.txt`（修正前は `results_poc_output.txt`）。

| ID | 状況 | 修正内容 | PoCの結果（修正前 → 後） |
| --- | --- | --- | --- |
| SEC-001 | 修正済 | ログインの入力長を検証（username 64／password 128）、リミッターのキーを SHA-256 に | 保持キー 30,000,012文字 → 64文字、25M文字 → 400 |
| SEC-002 | 修正済 | `ImageInspector`（画素数の上限25MP。管理者・公開で共通）、回転は12MP超なら行わない | 17KBのPNG: OutOfMemoryError → `rotate returned null` |
| SEC-004 | 修正済（一部残り） | 全角・区切り文字違い・「お客様番号」・氏名の空白・担当者・全角メールを検出、`receiptNumber` も処理 | 全角電話・中黒カード等が検出される。**残り**: 市区町村だけの住所、22桁の数字、`(at)` 表記 |
| SEC-006 | 修正済 | 正規表現を線形化（占有量指定子、後読み、長さ制限） | 空白2000個: 8959ms → 9ms、英数字8万文字: 53369ms → 169ms |
| SEC-008 | 修正済 | ロック中の記録を追い出さない | `adminAcceptedAfterFlood`: true → false |
| SEC-009 | 修正済 | CSP・Referrer-Policy・Permissions-Policy を追加 | ヘッダーあり |
| SEC-010 | 修正済 | `server.forward-headers-strategy=framework` | リダイレクトが https、HSTS が付く |
| SEC-011 | 修正済 | コメント拒否、要素数≤300、深さ≤12、width/height≤1000、`stroke-dasharray`≥1 | コメント内の `<script>`: 合格 → 拒否 |
| SEC-012 | 一部修正 | AIのモンスター名はカタカナのみ、説明文・技名はURL風の文字列を拒否、店舗／商品カテゴリーは許可リスト | **残り**: 偽造レシートで強いカードを作れる点（仕様判断） |
| SEC-013 | 修正済 | `resolve-lazily=true` とコントローラーの順序変更で、認可・回数制限の後に本体を読む | 存在しないルームへの4.9MB: 全量受信 → 送信中に切断、403 |
| SEC-014 | 修正済（一部） | 席が必要なAPIは、ルームがなくても同じ403 | 存在する／しない: 403／404 → 403／403。**残り**: 失敗した認可へのレート制限 |
| SEC-015 | 修正済 | 管理者のアップロードも先頭バイト・画素数を検査 | 画像でないファイル: 受理 → 415 |
| SEC-016 | 修正済 | 非root実行、`MaxRAMPercentage=70`、`ExitOnOutOfMemoryError`（ダイジェスト固定は未対応） | — |
| SEC-017 | 修正済 | `render.yml` に `ipAllowList: []` | — |
| VAL-001〜003 | 修正済 | Bean Validation と `ApiExceptionHandler`（前回の作業） | 不正JSON等: 500 → 400／415／413 |
| VAL-004 | 修正済 | 不正な `tableName` は 404 | 500 → 404 |
| VAL-005 | 修正済 | 合計・金額を範囲内に収める | `total=Long.MIN`: HP -214748145 → 295 |
| VAL-007 | 修正済 | Geminiの出力を件数・長さ・範囲・許可リスト・日時の範囲で正規化 | — |
| GEN-001 | 修正済 | `resetAnalyzer` を `CASCADE`、`reFront/test` の期待値を更新 | 4件のエラー → 0 |
| GEN-002 | 修正済 | `reFront/app.js` をバックエンド版に同期、`config.js` の意図した差だけを許容 | node のテスト 1件失敗 → 0 |
| GEN-003, 005 | 修正済 | 5xx のログ出力、タスク拒否時のログ | — |
| reFront／管理画面JS | 修正済 | 画像のサイズ（5MiB）・空ファイル、ZIPの件数（50）・展開後の大きさ（1枚5MiB・合計100MiB、展開中に打ち切り）をブラウザー側でも検査。ログインの入力長（64／128）。`server.mjs` は Host 検証・ドットファイル非配信・不正な%エンコードを400・GET/HEAD以外を405に | ZIP爆弾でブラウザーのメモリを使い切る → 打ち切り。`Host: evil.example` 200 → 403、`/.gitignore` 200 → 404 |
| **現状維持（判断済み）** | | | |
| SEC-003 | 現状維持 | ロックアウト方式は変更しない。リスク（管理者のログインを15分ブロックされうる）を承知の上で運用する | |
| SEC-005 | 現状維持 | 公開するレシートの項目（レシート番号・支店名・時刻）は変更しない | |
| SEC-007 | 現状維持 | `X-Forwarded-For` の扱いは変更しない。Renderで末尾が本物のIPでない場合、回数制限が効かない／全員で共有されるリスクを承知の上で運用する | |
| SEC-004（残り）／SEC-012（残り） | 現状維持 | 市区町村だけの住所・22桁の数字・`(at)` 表記の未検出、偽造レシートで強いカードを作れる点 | |
| SEC-018 | 現状維持 | Google側の予算上限・有料枠の確認はコード外の運用事項として扱う | |
| SEC-019 | 一部対応 | 画像がGoogleへ送られ、無料枠のキーでは製品改善（AIの学習を含む）に使われうることを、入口ページ・対戦画面の「レシート画像から作る」・解析画面に注意書きとして表示した。有料枠の確認とEXIF除去は行わない（キーの枠は管理者が決める） | |
| VAL-006 | 現状維持 | Jacksonの型変換は厳格化しない | |
| GEN-004 | 現状維持 | DB負荷の改善は行わない | |

---

## 1. 総評

認可まわりの土台は堅い。管理者APIは匿名で到達できず、CSRF・セッション固定・ログアウト・パス細工・SQLインジェクション・席トークンの扱い・対戦の同時操作は、PoCで抜け道を見つけられなかった（第7章）。フロントのXSS対策（`esc()`／`textContent`）とSVGの `<img>` 表示も有効に働いている。

一方、**ログイン不要の公開部分に、入力サイズ・画像サイズの上限がないことによるメモリ枯渇（SEC-001、SEC-002）** がある。Render無料枠（JVM既定ヒープ約128MB）では、少数のリクエストでプロセスが落ちる。また、個人情報の除去は全角ハイフン・全角数字・「お客様番号」などですり抜け、その結果が匿名ユーザー全員に表示される（SEC-004、SEC-005）。

バリデーションは手作業で行っているが、**クライアント起因のエラー（壊れたJSON、Content-Type違い、5MB超のアップロード）がすべて500になる**（VAL-001）、DBの桁あふれ・NUL文字が「PostgreSQLとの通信に失敗」の503に化ける（VAL-002）など、「型・長さ・範囲」の検証が薄い。既存テストは `mvn test` で4件、`node --test` で1件が赤いままで、回帰を検知する仕組みとして頼れない（GEN-001）。

## 2. 重大度別の件数

| 重大度 | 確定 | 要確認 | 合計 | ID |
| --- | ---: | ---: | ---: | --- |
| Critical | 0 | 0 | 0 | — |
| High | 1 | 1 | 2 | SEC-001（確定）／SEC-002（要確認） |
| Medium | 8 | 1 | 9 | 確定: SEC-003, 004, 005, 006, VAL-001, 002, 003, GEN-001／要確認: SEC-007 |
| Low | 16 | 3 | 19 | 確定: SEC-008, 009, 011, 012, 013, 014, 015, 018, VAL-004, 005, 006, 007, GEN-002, 003, 004, 005／要確認: SEC-010, 016, 017 |
| Info | 3 | 1 | 4 | 確定: SEC-020, 021, GEN-006／要確認: SEC-019 |
| **合計** | **28** | **6** | **34** | |

- 「確定」= 入口（HTTPリクエスト）から問題のコードまで到達できることを、PoCの実行またはコードの追跡で確かめたもの。
- 「要確認」= コード上の問題は確かめたが、到達にRender側の挙動やGeminiの応答など、手元で再現できない条件が要るもの。各指摘に条件を書いた。
- SEC-006 は管理者の経路では確定、公開（image-card）の経路は「Geminiが長い空白列を出力する場合」が条件のため要確認。件数は確定に数えた。

### システム概要の訂正・補足（プロンプトの記述との差）

- 記述どおり: Java 21 / Spring Boot 4.1（実際は 4.1.0）、管理者1件のInMemory認証、席トークン、CSRF対象外の `/api/monster/**`、テーブル名の文字列連結。
- 補足1: **`reFront/` の `app.js`・`select.js`・`login.js`・`admin-api.js` はどのHTMLからも読み込まれていない**（`reFront/` に `login.html` 等がなく、`index.html` は `config.js`・`index.js` だけを使う）。実際に動く管理画面は `reBack/.../static/admin/` のみ。複製は死んだコード（GEN-002）。
- 補足2: CSRF Cookie（`XSRF-TOKEN`）は `HttpOnly` で、トークンは `GET /api/auth/csrf` のJSONで受け渡している。
- 補足3: 席トークンは `sessionStorage` に保存される（`game.js:26`）。タブを閉じると失われる。

---

## 3. エンドポイント一覧

認可は `SecurityConfig.java:78-88` の順序どおり。表の「ログイン済みのみCSRF」は `requireCsrfProtectionMatcher`（同:66-76）の挙動。ログイン前のリクエストは認可で先に弾かれるため、この条件に抜け道はなかった（PoC `CSRF|*`）。

### API

| パス | メソッド | 認可 | CSRF | レート制限 | 入力検証 |
| --- | --- | --- | --- | --- | --- |
| `/api/health` | GET | 公開 | — | なし | なし |
| `/api/auth/csrf` | GET | 公開 | — | なし | なし |
| `/api/auth/session` | GET | 公開（管理者のときだけユーザー名を返す） | — | なし | なし |
| `/api/auth/login` | POST | 公開 | 必須（なしは403） | ユーザー名単位のロック（5回/15分）。IP単位なし | **ほぼなし**（null・巨大文字列・型違いを許す。SEC-001, VAL-001） |
| `/api/auth/logout` | POST | 認証後に有効 | 必須 | なし | なし |
| `/api/receipts` | GET | ADMIN | —（GET） | なし | — |
| `/api/receipts/{tableName}` | GET | ADMIN | —（GET） | なし | `ReceiptTableName.isSafe`（正規表現）。不正は500（VAL-004） |
| `/api/receipts/{tableName}` | DELETE | ADMIN | ログイン済みなら必須 | なし | 同上 |
| `/api/receipts/analyze` | POST | ADMIN | ログイン済みなら必須 | なし（Geminiを呼ぶ） | Content-Type（クライアント申告）と5MBのみ（SEC-015） |
| `/api/receipts/save` | POST | ADMIN | ログイン済みなら必須 | なし | `sha256` の形式、`lines` の空判定のみ（VAL-002, 003, 005〜007） |
| `/api/monster/rooms` | POST | 公開 | **対象外** | IP単位 5回/10分 | なし |
| `/api/monster/rooms/{code}/join` | POST | 公開 | 対象外 | IP単位 10回/分 | コード形式（`[A-HJ-NP-Z2-9]{6}`） |
| `/api/monster/rooms/{code}` | GET | 席トークン | 対象外 | **なし** | コード形式。トークンはSHA-256後に定数時間比較 |
| `/api/monster/rooms/{code}/field` | GET | 席トークン | 対象外 | **なし**（SEC-005） | 同上 |
| `/api/monster/rooms/{code}/shuffle` | POST | 席トークン | 対象外 | **なし**（SEC-005） | 状態・手札の条件をサーバーで検査 |
| `/api/monster/rooms/{code}/flip` | POST | 席トークン | 対象外 | なし | `index` の範囲。型は緩い（VAL-006）、不正JSONは500（VAL-001） |
| `/api/monster/rooms/{code}/image-card` | POST | 席トークン | 対象外 | IP単位 10回/日 + キーごと 200回/日（メモリ上） | 先頭バイト（マジックナンバー）と5MB。**認可より前に本体を全量受信**（SEC-013） |
| `/api/monster/rooms/{code}/select` | POST | 席トークン | 対象外 | なし | `cardId` が自分の手札か画像カードか |
| `/api/monster/rooms/{code}/battle` | GET | 席トークン | 対象外 | なし | 状態がBATTLEか |
| `/api/monster/rooms/{code}/rematch` | POST | 席トークン | 対象外 | なし | `round` が現在のラウンドか |
| `/api/monster/rooms/{code}/leave` | POST | 席トークン | 対象外 | なし | — |
| 上記以外の `/api/**` | 全て | `denyAll`（匿名は401） | — | — | — |

### 静的ファイル

| パス | 認可 | 備考 |
| --- | --- | --- |
| `/admin/login.html`, `login.js`, `styles.css`, `config.js`, `admin-api.js` | 公開 | `config.js`・`admin-api.js` に秘密情報なし |
| `/admin/**`（上記以外: `menu.html`, `upload.html`, `select.html`, `app.js`, `select.js`） | ADMIN（未ログインは `/admin/login.html` へ302） | |
| `/game/**`, `/demo/**`, `/theme/**` | 公開（`anyRequest().permitAll()`） | 意図どおり。秘密情報なし。デモはモックデータのみ |
| `/application.yml`, `/.env` など | 404 | クラスパス直下は静的配信されない |
| `/error` | 公開 | 直接GETすると500（GEN-006） |

PoC `MATRIX|*` で40パターン（大文字小文字、`//`、`;`、`%2e%2e`、`..`、`.json`、`%61pi` など）を試し、いずれも200/401/400/404のいずれかで、**管理者APIに匿名で到達できるパスはなかった**。

---

## 4. 指摘一覧（重大度順）

### High

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-001** |
| 重大度 | High |
| 確度 | 確定 |
| 分類 | DoS（メモリ枯渇） |
| 場所 | `reBack/src/main/java/com/example/receipt/controller/AuthController.java:63,67`、`.../service/LoginAttemptLimiter.java:30,121-123` |
| 内容 | `/api/auth/login` は未認証で、`username` の長さを一切検証しない。`LoginAttemptLimiter` は失敗した `username`（trim + 小文字化した全文）を **そのままMapのキー** として15分間（最大10,000件）保持する。リクエストボディにサイズ上限がなく、Jackson 3.1.4 は25M文字の文字列を受け付けた。 |
| 攻撃シナリオ | 攻撃者5（匿名）。CSRFトークンを取得（`GET /api/auth/csrf`）し、25MBの `username` を持つログインを数回送る。1回につき約25MBがMapに残るので、Render無料枠のJVM既定ヒープ（約128MB。512MBの1/4）は数リクエストで枯渇し、`OutOfMemoryError` でサービス停止・再起動になる。再起動でレート制限・Gemini日次カウンタもリセットされる（SEC-018）。 |
| 再現手順 | `review/poc/src/test/java/poc/HttpPocTest.java` の `loginInputsAndLockout`。結果: `POC|LOGIN-HUGE|5M文字のusername -> status=401`、`25M文字 -> status=401`、`limiterが保持しているキー数=5 合計文字数=30000012`（`results_poc_output.txt`）。curl は `review/poc/curl_examples.sh 1`（ローカル専用）。OOM自体は実測していない（保持量からの計算）。 |
| 修正案 | ①`login` の入口で `username` ≤ 64文字、`password` ≤ 128文字を検査し、超えたら **キーを保存せず** 400/401を返す。②リミッターのキーは `SHA-256(lowercase(username))` にして長さを固定する。③`/api/auth/login` にだけ小さなボディ上限を付ける（`OncePerRequestFilter` で `Content-Length` ≤ 4KB、またはJacksonの `StreamReadConstraints.maxStringLength` を小さくする）。 |

```java
// AuthController.login の先頭
if (login == null || login.username() == null || login.password() == null
        || login.username().length() > 64 || login.password().length() > 128) {
    return ResponseEntity.badRequest().body(Map.of("message", "入力が正しくありません。"));
}
```

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-002** |
| 重大度 | High |
| 確度 | 要確認（関数単体の挙動は確定。到達には「GeminiがtextRotationに90/180/270を返す」ことが条件） |
| 分類 | DoS（画像ボム） |
| 場所 | `reBack/src/main/java/com/example/receipt/service/GeminiReceiptAnalyzer.java:131-142,251-281`（特に253行の `ImageIO.read`）、公開側の入口 `MonsterRoomService.java:201-276`、`MonsterRoomService.java:556-571` |
| 内容 | 画像の縦横サイズを一切検証しない。Geminiが回転角（90/180/270）を返すと `rotateClockwise` が `ImageIO.read` で画像を全画素デコードし、さらに `TYPE_INT_RGB` の同サイズ画像を確保する。`catch (IOException \| RuntimeException)` なので `OutOfMemoryError` は捕まらない。マジックナンバーの検査（`detectImageType`）は先頭数バイトだけ見るため、画像ボムを通す。 |
| 攻撃シナリオ | 攻撃者3/5。ログイン不要で、ルーム作成→参加後にカード選択画面で `image-card` へ、**縦横12,000px・17KBのPNG**（文字を横向きに書いておき、プロンプトインジェクションで回転角を指示する）を送る。Geminiが回転を返せば、サーバーのJVMが `OutOfMemoryError` になる。1日10回/IPの制限は、IPを変える（SEC-007）か、1回の成功で十分なため防げない。管理者の `/api/receipts/analyze` も同じ経路を通る。 |
| 再現手順 | `review/poc/src/test/java/com/example/receipt/service/ImageBombPocTest.java`。結果: `POC|SEC-IMGBOMB|pngBytes=17582 pixels=144000000`、`-Xmx128m exit=0 output=THROWN java.lang.OutOfMemoryError`、`-Xmx256m ... OutOfMemoryError`（`results_poc_output.txt`）。 |
| 修正案 | ①`ImageIO.createImageInputStream` + `ImageReader.getWidth/getHeight`（デコードしない）で縦横を読み、例えば 4096×4096（約16MP）超は拒否する。この検査を **管理者・公開の両方で共通のバリデータ**（`ReceiptUploadValidator`）に集約する。②`rotateClockwise` の `catch` を `Throwable` 相当（少なくとも `OutOfMemoryError`）にするより、事前検査で防ぐ。③Dockerfileに `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError` を付け、OOM時は中途半端に生き残らず再起動させる（SEC-016）。 |

### Medium

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-003** |
| 重大度 | Medium |
| 確度 | 確定 |
| 分類 | DoS（管理者のロックアウト） |
| 場所 | `reBack/.../service/LoginAttemptLimiter.java:49-68,79-100`、`AuthController.java:67-71` |
| 内容 | ロックはユーザー名だけが鍵で、IPの観点がない。ロック中は **正しいパスワードでも照合しない**（設計意図どおり）。管理者は1人で、ユーザー名は推測しやすい（`/api/auth/session` はログイン後にユーザー名を返す）。 |
| 攻撃シナリオ | 攻撃者1。管理者のユーザー名で5回ログインを失敗させるだけで、15分間、管理者がログインできなくなる。15分ごとに繰り返せば、管理画面（レシートの登録・削除）を事実上使えなくできる。ロック応答は存在しないユーザー名でも同一（ユーザー列挙はできない。PoC `ENUM|*`）。 |
| 再現手順 | `HttpPocTest.loginInputsAndLockout`。結果: `POC|SEC-LOCK-DOS|5回失敗後に正しいパスワードで -> 429`。curl は `curl_examples.sh 3`。 |
| 修正案 | 「ユーザー名単位のハードロック」をやめ、**(IP, ユーザー名) 単位の失敗カウント + 指数バックオフ（遅延）** にする。ログイン成功の可能性を残すため、ロックではなく `Retry-After` 付きの遅延にするか、既知の端末（署名付きCookie）は制限の対象外にする。管理者へのメール等の通知（ロックが発生した事実）も有効。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-004** |
| 重大度 | Medium |
| 確度 | 確定 |
| 分類 | 情報漏えい（個人情報の除去漏れ） |
| 場所 | `reBack/.../monster/engine/PersonalInfoSanitizer.java:22-39,85-117`、`ReceiptService.java:55-74` |
| 内容 | 除去は正規表現で、全角ハイフン・全角数字だけの電話番号、`.` 区切りの電話番号、中黒区切りのカード番号、22桁の数字、半角全角混在などをすり抜ける。「お客様番号 1234567」は `様` の規則が先に当たり、**番号の部分が残る**（`（非表示）番号 1234567`）。「山田 太郎 様」（空白あり）・「ご担当 佐藤」・「担当：鈴木」は検出されない。住所は都道府県で始まらないと検出されない（「名古屋市中区栄3-1-1」）。`receiptNumber`・`storeCategory`・品目の `category` は処理の対象外で、そのまま保存・表示される。 |
| 攻撃シナリオ | 攻撃者1/2。除去をすり抜けた文字列は保存され、**対戦に参加した誰にでも**表示される（SEC-005）。レシートの主（管理者や、その同伴者）の氏名・番号が公開されうる。 |
| 再現手順 | `LogicPocTest.piiEvasion`、`sanitizerLeavesReceiptNumberAndCategoryUntouched`。結果は `results_poc_output.txt` の `POC|PII|*`。例: `TEL ０３－１２３４－５６７８ -> masked=0`、`お客様番号 1234567 -> （非表示）番号 1234567`、`山田 太郎 様 -> masked=0`、`4111·1111·1111·1111 -> masked=0`、`POC|PII-RECEIPTNO|receiptNumber=090-1234-5678`（未処理）。 |
| 修正案 | ①マッチの前に `Normalizer.normalize(text, Normalizer.Form.NFKC)` を通す（全角数字・全角記号・全角`@`が半角になる）。NFKC で変わらない `‐`（U+2010）・`ー`・`·`・`.` は区切り文字クラスに足す。②電話・カード・会員番号は「区切りを無視して数字が10桁以上続く」ことで一括して伏せる方式にする。③`お客様番号` は先に番号側の規則を当てる（順序の入れ替え）。④`receiptNumber` も処理する（または表示しない）。⑤品目名・店名に対して「人名らしい」検出は難しいので、**公開する項目自体を減らす**（SEC-005）。⑥上記ケースを `PersonalInfoSanitizerTest` に追加する。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-005** |
| 重大度 | Medium |
| 確度 | 確定 |
| 分類 | 情報漏えい（匿名での大量取得） |
| 場所 | `MonsterRoomService.java:154-174,493-508,451-474`、`MonsterViews.java:341-354`、`MonsterController.java:63-76`、`ReceiptTableRepository.java:216-238` |
| 内容 | ログイン不要でルームを作ると、保存済みレシート最大9件の **店名・支店名・購入日時（分まで）・レシート番号・全品目・合計・支払方法** が見える。`shuffle`（10件以上あるとき）は回数制限がなく、`field` にも制限がない。対戦後は相手のカードの元レシートも見える。ルーム作成はIP単位5回/10分だが、SEC-007のとおり回避しうる。削除したレシートの **コピー** は、進行中のルーム（`monster_play.field_json`、`monster_battle.result_json`）に最長120分残り、見え続ける。 |
| 攻撃シナリオ | 攻撃者1。スクリプトで `create → field → shuffle × N` を繰り返し、保存済みレシートの全件を取得する（「誰が・いつ・どの店の・どの支店で・何を買ったか」）。SEC-004の除去漏れと組み合わさると、個人を特定できる情報が漏れる。 |
| 再現手順 | `HttpPocTest.monsterApi`。結果: `POC|MONSTER-FIELD|最初のレシートの項目=[storeCategory, storeName, branchName, purchasedAt, receiptNumber, items, totalAmount, paymentMethod]`、`POC|MONSTER-BATTLE|相手に見えるレシート項目=[...] receiptNumber="No7" branchName="支店7"`。応答に `sha256`・テーブル名は含まれない（確認済み）。 |
| 修正案 | 「ログイン不要で公開する」のは仕様だが、**公開範囲を最小にする**: ①`receiptNumber`・`branchName` は表示しない。購入日時は日付（または時間帯）に丸める。②`shuffle`・`field` にIP/席単位のレート制限（例: 1ルームあたり `shuffle` 3回まで）。③レシート削除時に `monster_play`／`monster_battle` の該当コピーも消す（または、該当カードを含む進行中ルームを閉じる）。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-006** |
| 重大度 | Medium |
| 確度 | 確定（管理者経路）／要確認（公開経路: Geminiが「会員」+長い空白列、または数万文字の英数字だけの行を出力した場合） |
| 分類 | DoS（正規表現の計算量） |
| 場所 | `PersonalInfoSanitizer.java:24`（会員番号の規則）、`:29`（メールの規則）、呼び出し元 `ReceiptService.java:64`、`MonsterRoomService.java:233`、`MonsterCardService.java:65` |
| 内容 | ①会員番号の規則 `(?:会員\|カード\|ポイント\|ID)\s*(?:番号\|No\.?)?\s*[:：]?\s*\d...` は、`\s*` が3つ連続するため、「会員」の後に空白が続き数字が来ない入力で **O(n³)**。②メールの規則 `[A-Za-z0-9._%+-]+@...` は `@` のない長い英数字列で **O(n²)**（各開始位置から末尾まで走査する）。 |
| 攻撃シナリオ | 攻撃者3/5。管理者の `/save` では任意のJSONを送れるため確定。公開の `image-card` では、画像内のテキストでGeminiに「会員」+3,000個の空白を出力させる（プロンプトインジェクション）と、サニタイズ処理がリクエストスレッドを数十秒占有する。`prepareDraftAsync`（管理者の解析後）は2スレッドしかない `MonsterTaskRunner` で動き、ここが塞がるとCPU対戦の思考（`cpuTurn`）も止まる（GEN-005）。 |
| 再現手順 | `LogicPocTest.piiRegexRedos`、`piiEmailRegexIsQuadraticOnLongAlnumLine`。結果: 「会員」+空白 250/500/1000/2000個 → 478/222/1161/**8959 ms**（4000個は初回実測で約106秒）。全角空白 1000個 → 2825 ms。英数字だけの行 10,000/20,000/40,000/80,000文字 → 815/3089/12488/**53369 ms**。HTTP経由: `POC|SAVE|lines=[英数字だけの1行 30,000文字] -> 200 OK (14485ms)`。 |
| 修正案 | ①規則の入力長を制限する（1行 500文字を超えたら切り詰めるか、拒否）。②正規表現を線形にする: 会員番号は `(?:会員\|カード\|ポイント\|ID)[\s:：]*+(?:番号\|No\.?)?[\s:：]*+\d(?:[ -]?\d){3,}`（占有量指定子 `*+` と、連続する `\s*` の統合）。メールは `(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,255}\.[A-Za-z]{2,}`（先頭の後読みで再試行を防ぎ、長さを制限）。③万一に備え、`CharSequence` を包んで一定時間で例外を投げる「タイムアウト付きマッチ」を使う。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-007** |
| 重大度 | Medium |
| 確度 | 要確認（Renderの `X-Forwarded-For` の実際の形が未確認） |
| 分類 | レート制限の回避 / 誤制限 |
| 場所 | `reBack/.../monster/MonsterRateLimiter.java:44,77-90,104-107,112-123`、`reBack/render.yml:31-32` |
| 内容 | `MONSTER_TRUST_FORWARDED_FOR=true` のとき、`X-Forwarded-For` の **最後の要素** をクライアントIPとして使う。①Renderのプロキシが自分で値を **追記** するなら、最後は実クライアントになり、偽装できない（PoCでも「先頭だけ偽装」は効かない）。②Renderの前段（CDN等）が最後に自分のIPを追記する構成なら、**全利用者が少数のIPに集約され、5回/10分が全員で共有される**（誤制限）。③プロキシが値を追記・上書きしない構成なら、ヘッダーを毎回変えるだけで全制限を回避できる。さらに、IPv6は `/64` に丸めておらず（アドレスごとに別扱い）、`hits` のMapは20,000件を超えても、直近1日以内のエントリは捨てないので **無制限に増える**（`prune` は `isEmpty` か1日以上前のものだけを削る）。 |
| 攻撃シナリオ | 攻撃者5。③またはIPv6の複数アドレスで、ルーム作成（DBの行を増やす）・参加の総当たり・画像カード（Gemini呼び出し）の制限を回避する。ただしGeminiは、キーごとの1日200回の上限（メモリ上）が別にあり、費用は頭打ちになる（SEC-018）。 |
| 再現手順 | `HttpPocTest.rateLimitAndForwardedFor`（`trust-forwarded-for=true`）。結果: `同一XFFで7回create=[200×5, 429, 429]`、`XFFを変えながら12回create=[200×12]`（回避できる）、`先頭を偽装・末尾固定で7回create=[200×5, 429, 429]`（効かない）。`curl_examples.sh 7`。Renderの実際の値は **ログに `X-Forwarded-For`・`True-Client-IP`・`CF-Connecting-IP` を一度だけ出して確認すること**（未実施）。 |
| 修正案 | ①Renderで実際のヘッダーを確かめ、「末尾から N 番目」（信頼するプロキシ段数を設定値にする）または専用ヘッダー（`True-Client-IP` 等）を使う。②`server.forward-headers-strategy=framework` を設定して `request.getRemoteAddr()` に任せる方法もある（SEC-010）。③IPv6は上位64bitに丸める。④`hits` のMapには件数の上限を設け、超えたら古いエントリを必ず捨てる。 |

| 項目 | 内容 |
| --- | --- |
| ID | **VAL-001** |
| 重大度 | Medium |
| 確度 | 確定 |
| 分類 | バリデーション / エラー処理 |
| 場所 | `reBack/.../controller/ApiExceptionHandler.java:69-72`（`@ExceptionHandler(Exception.class)`）、同:20-62 |
| 内容 | `Exception` を受ける汎用ハンドラが、Spring標準の400/415/413の変換より優先される。そのため、**壊れたJSON・空のボディ・Content-Type違い・型違い（文字列が来るべき所に数値など）・日付形式違い・int範囲外・5MB超のアップロード（`MaxUploadSizeExceededException`）** がすべて `500 INTERNAL_ERROR` になる。例外はログにも出ない（GEN-003）。 |
| 攻撃シナリオ | 攻撃者1。意図的に500を大量に出して監視のノイズにする、障害と区別できなくする。正規利用者にとっても、入力ミスが「サーバー内部エラー」に見える。 |
| 再現手順 | `HttpPocTest`。結果（抜粋）: `LOGIN-INPUT 空/null/[]/not json/text-plain -> 500`、`SAVE 壊れたJSON/Content-Type text/plain/空ボディ -> 500`、`SAVE purchasedAt 不正 -> 500`、`SAVE amount が文字列 -> 500`、`SAVE lines が文字列(型違い) -> 500`、`MONSTER-FLIP {"index":99999999999}/null/空/[0]/Content-Type無し -> 500`、`UPLOAD analyze: 6MiB -> HTTP/1.1 500`、`analyze: boundaryなし/Content-Type=json -> 500`。curl は `curl_examples.sh 4, 5`。 |
| 修正案 | 個別のハンドラを足す（下記）。あわせて、5xxの例外は `LOGGER.error` でスタックトレースをサーバーログにだけ出す（応答には出さない）。 |

```java
@ExceptionHandler(HttpMessageNotReadableException.class)
ResponseEntity<ErrorResponse> badJson(HttpMessageNotReadableException e) { return error(400, "INVALID_REQUEST_BODY", "リクエストの形式が正しくありません。"); }
@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException e) { return error(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Typeが正しくありません。"); }
@ExceptionHandler(MaxUploadSizeExceededException.class)
ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) { return error(413, "FILE_TOO_LARGE", "画像ファイルのサイズが上限を超えています。"); }
@ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingRequestHeaderException.class, MultipartException.class})
ResponseEntity<ErrorResponse> badRequest(Exception e) { return error(400, "INVALID_REQUEST", "リクエストが正しくありません。"); }
```

| 項目 | 内容 |
| --- | --- |
| ID | **VAL-002** |
| 重大度 | Medium |
| 確度 | 確定（PostgreSQL 18 で確認） |
| 分類 | バリデーション |
| 場所 | `ApiExceptionHandler.java:32-40`、`ReceiptTableRepository.java:119-131,136-147,149-170`、`ReceiptService.java:55-74` |
| 内容 | 長さ・文字の検証がなく、DBの制約違反（`VARCHAR(255)` の `storeName`、`VARCHAR(50)` の `storeCategory`／品目の `category`、`NUMERIC(12,3)` の `quantity`）と、PostgreSQLが拒否する **NUL文字（`\u0000`）** が、`DataAccessException` として **503「PostgreSQLとの通信に失敗しました。時間をおいて再度保存してください」** に化ける。再試行しても必ず失敗する。Geminiの出力にも同じ値が混ざりうる（公開側の `insertDraft` で `store_category VARCHAR(50)` 超過 → 503）。 |
| 攻撃シナリオ | 管理者の操作ミス、またはGeminiの出力（プロンプトインジェクション）で保存が必ず失敗する。公開の `image-card` でも、画像内の指示でGeminiに50文字超の `storeCategory` を出させると、その画像のカード作成が503で失敗し、Geminiの呼び出しとクォータだけが消費される。 |
| 再現手順 | `PgPocTest`（PostgreSQL 18）: `NUL(\u0000)を含む行 -> DataIntegrityViolationException: ... invalid byte sequence for encoding "UTF8": 0x00`、`storeName 300文字 -> DataIntegrityViolationException`、`NULを含むstoreName -> DataIntegrityViolationException`。HTTP経由（H2）: `SAVE storeName 300文字/storeCategory 100文字/quantity 1e30/quantity 1E999999999 -> 503 DATABASE_ERROR`。※H2はNULを受け付けるので、NULの件はPostgreSQLでのみ再現する。 |
| 修正案 | ①入力の入口で長さ・文字を検証して400を返す（`storeName`／`branchName` ≤ 255、`storeCategory`／`category` は許可リスト、`paymentMethod` ≤ 100、`receiptNumber` ≤ 100、`quantity` は `|x| < 10^9` かつ小数3桁、各行 ≤ 500文字）。②文字列から `\u0000` と制御文字（改行・タブ以外）を取り除く。③`DataIntegrityViolationException` は400/422に、`DataAccessResourceFailureException`／`QueryTimeoutException` だけを503にする。 |

| 項目 | 内容 |
| --- | --- |
| ID | **VAL-003** |
| 重大度 | Medium |
| 確度 | 確定 |
| 分類 | バリデーション / DoS |
| 場所 | `ReceiptController.java:58-62`、`ReceiptService.java:55-74`、`ReceiptTableRepository.java:119-131`、`application.yml`（ボディ上限の設定なし） |
| 内容 | `/api/receipts/save` の `lines` の件数・1行の長さ・`items` の件数・ボディ全体の大きさに上限がない。 |
| 攻撃シナリオ | 管理者のブラウザが乗っ取られた場合（攻撃者4）、または不具合・誤操作で、DBの肥大化とCPU占有（SEC-006）が起きる。 |
| 再現手順 | `HttpPocTest.adminApiInputs`。結果: `SAVE 200000行 -> 200 ... (3222ms)`、`lines=[英数字だけの1行 30,000文字] -> 200 OK (14485ms)`。ログインAPIは25M文字の文字列も受理（SEC-001）。 |
| 修正案 | `lines` ≤ 1000行（`GeminiReceiptAnalyzer.java:244` の `limit(1000)` と合わせる）、1行 ≤ 500文字、`items` ≤ 200件、`/api/receipts/save` のボディ ≤ 1MB。フィルターで `Content-Length` を見て413にする。 |

| 項目 | 内容 |
| --- | --- |
| ID | **GEN-001** |
| 重大度 | Medium |
| 確度 | 確定 |
| 分類 | テスト |
| 場所 | `reBack/src/test/java/com/example/receipt/controller/ReceiptControllerIntegrationTest.java:46-54`、`reFront/test/smoke.test.mjs:153-159` |
| 内容 | ①`mvn test`: 136件中 **4件がエラー**。`resetAnalyzer`（`@BeforeEach`）が `receipt_%` に一致する全テーブルを順に `DROP` するが、`receipt_structured_summary` が `receipt_structured_item` の外部キーで参照されているため `DROP` に失敗する（テストの後始末の不備。本体の不具合ではない）。②`node --test reFront/test` は **Node 22 ではディレクトリ指定で起動できない**（`Cannot find module '.../reFront/test'`）。`node --test "reFront/test/*.test.mjs"` なら23件中 **1件が失敗**: 「reFront copies of admin scripts match the files served by Backend」（`app.js` が不一致。`config.js` も不一致だが、最初の差で止まるため表に出ない）。 |
| 攻撃シナリオ | テストが常時赤いため、本物の回帰（例えばCSRFや認可の退行）が埋もれる。 |
| 再現手順 | `review/poc/mvn_test.log`（136件、エラー4）、`review/poc/node_test.log`（23件、成功22、失敗1）。 |
| 修正案 | ①`resetAnalyzer` で `receipt_structured_item` → `receipt_structured_summary` の順に消すか、`DROP TABLE ... CASCADE` にする。②`package.json` の `test` スクリプトを `node --test "test/*.test.mjs"` にする。③`reFront` の複製を無くす（GEN-002）。 |

### Low

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-008** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | 認証（ブルートフォース対策の回避） |
| 場所 | `LoginAttemptLimiter.java:103-119` |
| 内容 | 記録が10,000件に達すると、期限切れを捨て、それでも足りなければ照合中でない **最も古い記録（ロック中のものを含む）** を捨てる。 |
| 攻撃シナリオ | 攻撃者1。10,001種類の別ユーザー名で失敗を重ねると、管理者のロック記録が追い出され、失敗回数が0に戻る。これで「5回の試行」を取り戻せる。ただし1サイクルに約1万リクエスト（存在しないユーザーにもbcryptの空照合が走る）が必要で、現実的な総当たりには遅すぎる。 |
| 再現手順 | `LogicPocTest.limiterEvictionLetsAttackerResetAdminLock`。結果: `POC|SEC-LOCK-DOS|adminLockedAfter5Failures=true`、`POC|SEC-LOCK-EVICT|adminAcceptedAfterFlood=true`。 |
| 修正案 | ロック中のエントリは追い出さない。上限に達したら新しい失敗の記録を拒否する（既存のロックを守る）。SEC-001の対策（キーのハッシュ化・長さ固定）もあわせて入れる。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-009** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | HTTPヘッダー |
| 場所 | `SecurityConfig.java:59-101`（`headers` の設定なし） |
| 内容 | Spring Securityの既定で `X-Frame-Options: DENY`・`X-Content-Type-Options: nosniff`・`Cache-Control: no-store` は付く（確認済み）。**`Content-Security-Policy`・`Referrer-Policy`・`Permissions-Policy` はない。** CORSは未設定で、別オリジンからのプリフライトに `Access-Control-Allow-Origin` は付かない（安全側）。XSSは見つからなかったので、CSPは多層防御の位置づけ。 |
| 攻撃シナリオ | 攻撃者4。将来XSSが混入した場合の被害（管理画面のCookie操作は `HttpOnly` で防げるが、管理操作の実行は可能）を抑える層がない。 |
| 再現手順 | `HttpPocTest.endpointMatrixAndHeaders`、`redirectAndHsts`。結果: `POC|HEADERS|/game/index.html {... x-content-type-options=[nosniff], x-frame-options=[DENY] ...}`、`POC|HSTS|... Referrer-Policy=なし / CSP=なし / Permissions-Policy=なし`、`POC|CORS|preflight from evil origin -> 200 ACAO=なし`。 |
| 修正案 | 全ページがインラインスクリプトを使っていない（`<script src>` のみ。確認済み）ので、厳格なCSPが入れられる。`data:` はゲームのカード画像（`<img src="data:image/svg+xml,...">`）に必要。`config.js` の `innerHTML` に `style="..."` があるため、`style-src` には当面 `'unsafe-inline'` が要る（または `class` に置き換える）。 |

```java
http.headers(h -> h
    .contentSecurityPolicy(c -> c.policyDirectives(
        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
      + "font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; "
      + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'; object-src 'none'"))
    .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
    .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=()")));
```

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-010** |
| 重大度 | Low |
| 確度 | 要確認（Renderが `X-Forwarded-Proto` を付けるか、HSTSをRender側が付けるかは未確認） |
| 分類 | HTTPヘッダー / プロキシ設定 |
| 場所 | `application.yml`（`server.forward-headers-strategy` の設定なし） |
| 内容 | `forward-headers-strategy` が未設定のため、Tomcatは常に `http` と判断する。①Spring Securityが **HSTSを出さない**（`request.isSecure()` が偽）。②認証エントリポイントの `sendRedirect("/admin/login.html")` が **`http://` の絶対URL** を返す。③`request.getRemoteAddr()` がプロキシのIPになる。 |
| 攻撃シナリオ | 攻撃者1（ネットワーク上）。HSTSがないので、初回アクセスや証明書警告の無視でダウングレードされうる。リダイレクトのLocationが `http://` だと、1往復だけ平文になる（Render側が http→https を強制していれば実害は小さい）。 |
| 再現手順 | `HttpPocTest.redirectAndHsts`。結果: `POC|REDIRECT|status=302 Location=http://localhost:44133/admin/login.html HSTS(X-Forwarded-Proto:https付き)=なし`。 |
| 修正案 | `server.forward-headers-strategy=framework`（またはRenderで有効なら `native`）を設定し、`headers.httpStrictTransportSecurity(h -> h.includeSubDomains(true).maxAgeInSeconds(31536000))` を明示する。これを入れると `getRemoteAddr()` がクライアントIPになり、SEC-007の実装も簡単になる。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-011** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | XSS（多層防御）/ DoS（クライアント描画） |
| 場所 | `reBack/.../monster/engine/MonsterSvgValidator.java:28-32,57-91`（特に82-84の `COMMENT_NODE`） |
| 内容 | 検証したSVGと保存・配信するSVGは同一（`GeminiMonsterAi.java:145-146` で検証した文字列をそのまま返し、`withSvg` で保存。検証後の加工なし）。DOCTYPE・ENTITY・CDATA・属性・要素・`url(` はいずれも拒否され、XXE設定も有効（PoCで確認）。ただし **コメントノードは中身を問わず通す**ため、`<!-- "><script>alert(1)</script> -->` が合格する。また `width`／`height`／`stroke-dasharray`／要素数／ネストの深さに上限がなく、30,000文字以内で数千個の要素や極端なサイズ・点線を作れる。 |
| 攻撃シナリオ | 攻撃者3。現在の表示は `<img src="data:image/svg+xml;...encodeURIComponent(svg)">`（`game.js:23,105`）だけで、`<img>` 内ではスクリプトも外部読み込みも動かないため、**XSSにはならない**。将来 `innerHTML` やインライン表示に変えたとき、または描画負荷の高いSVGでブラウザを固まらせる場合にだけ問題になる。 |
| 再現手順 | `LogicPocTest.svgValidatorAcceptsCommentsWithMarkup`、`svgValidatorVariants`。結果: `POC|SEC-SVG-COMMENT|isSafe=true`、`huge-width/dasharray-tiny/single-quote-attr -> isSafe=true`、`char-ref-in-attr/style-attr/href-attr/entity/cdata -> false`、`SEC-SVG-NEST len=28066 isSafe=true`（スタック溢れなし）。 |
| 修正案 | コメントノードを拒否する（`case Node.COMMENT_NODE -> { return false; }`）。`viewBox` を `0 0 200 200` に固定、`width`／`height` を削るか上限（≤1000）を付ける、要素数 ≤ 300、深さ ≤ 10、`stroke-dasharray` は数値2〜4個・各値 ≥ 1 に制限する。表示は引き続き `<img>` だけに限定する。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-012** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | プロンプトインジェクション / ゲームバランス |
| 場所 | `GeminiReceiptAnalyzer.java:51-68,364-374`、`GeminiMonsterAi.java:55-121`、`MonsterCardGenerator.java:95,115,136-139,166-171` |
| 内容 | レシート画像内の文字でGeminiの出力（品目・金額・カテゴリー・日時）を自由に操作できる。数値は範囲にクランプされる（HP200〜700 など。AIのパラメータは `applyParameters` で検証）ので、**能力値の上限は破れない**。一方、文字列は制御文字の除去と文字数だけが検証で、**内容は自由**（`name`≤12文字、`skillName`≤12、`flavor`≤80。「カタカナ」という指示はプロンプトにあるが、サーバーでは検査しない）。`storeCategory`・品目の `category` は許可リストがなく、任意の文字列（HTML・長文）が `flavor` や応答に入る。偽造レシートで「ラッキー」（合計の末尾777、同じ時分、7のつく日）や高い金額を作れば、毎回強いカードを得られる。 |
| 攻撃シナリオ | 攻撃者3。①相手に表示される `flavor` や `name` に、URL・不適切な文言を入れる（HTMLは `esc()` で無害化されるので、スクリプト実行はできない）。②意図的に作った画像で強いカードを量産する（1日10回/IPまで）。③SEC-006・VAL-002の入力を作る。 |
| 再現手順 | `LogicPocTest.cardGeneratorWithHostileNumbers`（`CARD-CATEGORY-LEN|storeCategory.length=200 flavor.length=227`）。 |
| 修正案 | `name` は `^[ァ-ヶー]{1,12}$`、`skillName`・`flavor` は URL・`@`・制御文字を含まない文字種に制限する。`storeCategory`・`category` は許可リスト（スーパー／コンビニ／ドラッグストア／飲食店／その他、食料品／日用品／飲食／交通・移動／その他）に丸める。画像から作ったカードの扱い（ボーナスの有無）は仕様判断。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-013** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | DoS（認可前の受信） |
| 場所 | `MonsterController.java:84-93`（`@RequestParam("file") MultipartFile file`）、`application.yml`（`spring.servlet.multipart`） |
| 内容 | `/api/monster/**` は `permitAll`。Springは（`resolve-lazily` が既定の `false` のため）ハンドラーを呼ぶ前の `DispatcherServlet` の段階で、`multipart` を **先に全量受信・解析**する。席トークンの確認（`service.field`）もレート制限も、その後で行う。 |
| 攻撃シナリオ | 攻撃者5。存在しないルーム・誤ったトークンで、5MB弱のアップロードを大量に送ると、認証なしで帯域・一時ファイル・CPUを使わせられる。 |
| 再現手順 | `HttpPocTest.monsterApi`。結果: `POC|MONSTER-IMAGE|存在しないルーム + 4.9MB(認証前に全量受信される) -> HTTP/1.1 404  46ms`。5MB超は `HTTP/1.1 500`（VAL-001）。 |
| 修正案 | `spring.servlet.multipart.resolve-lazily=true` にして、コントローラー内で席トークンの確認・レート制限を済ませてから `request.getPart("file")` を読む。または `OncePerRequestFilter` で、`/image-card` に限りヘッダー（`X-Seat-Token` の形式）とIPごとの回数を先に確認する。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-014** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | 認可（ルーム列挙） |
| 場所 | `MonsterRoomService.java:386-396,579-592` |
| 内容 | トークンなしの `GET /api/monster/rooms/{code}` は、ルームが **ある場合は403、ない場合は404** を返す（存在の判別）。この系統（`state`・`field`・`battle`・`flip` など）にはレート制限がない。ルームコードは6桁×32種（約10億通り）で、`join` は10回/分/IPに制限されるが、存在判定は無制限に試せる。参加できるのは、待機中（作成から90秒）で、まだ誰も参加していないルームだけ。 |
| 攻撃シナリオ | 攻撃者1。存在するルームを探し当てて、待機中のうちに先に `join` し、相手を乗っ取る（相手の友人としてルームに入る）。確率は低い（同時に存在するルームが少ないため）。 |
| 再現手順 | `HttpPocTest.monsterApi`。結果: `POC|MONSTER-ORACLE|存在するルーム(token無)=403 存在しない=404 形式不正=400`。席の分離（トークンなし・誤り・別ルームの席 → 403）は確認済みで問題なし。 |
| 修正案 | トークンが無効なときは、ルームの有無にかかわらず同じ404（またはそろえた403）を返す。失敗した認可（403/404）にもIP単位のレート制限を付ける。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-015** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | ファイルアップロード |
| 場所 | `ReceiptUploadValidator.java:20-31`、`ReceiptService.java:41-53` |
| 内容 | 管理者の `/analyze` は `Content-Type`（クライアントが申告する値）と5MBだけを検査し、マジックナンバーを見ない。申告した `image/png` がそのままGeminiの `mimeType` になる。公開側の `detectImageType`（先頭バイト検査）とは **検査内容が違う**。どちらも画像の縦横を見ない（SEC-002）。 |
| 攻撃シナリオ | 管理者（またはその端末を乗っ取った攻撃者4）が、画像でないファイルをGeminiに送る。実害は小さい。 |
| 再現手順 | `HttpPocTest.adminApiInputs`。結果: `POC|UPLOAD|analyze: 中身が画像でない + Content-Type=image/png -> 200 analyzerCalls=1`、`analyze: Content-Type=image/gif -> 415`、`空ファイル -> 400`。 |
| 修正案 | `detectImageType` を `ReceiptUploadValidator` に移して両経路で共通化し、縦横の上限検査（SEC-002）も入れる。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-016** |
| 重大度 | Low |
| 確度 | 要確認（Renderのコンテナ設定に依存） |
| 分類 | インフラ |
| 場所 | `reBack/Dockerfile:1-12` |
| 内容 | ①`USER` の指定がなく **rootで実行**。②ベースイメージが可変タグ（`maven:3.9.11-eclipse-temurin-21-alpine`、`eclipse-temurin:21-jre-alpine`）でダイジェスト固定ではない。③JVMのメモリ設定がなく、OOM時のふるまい（`-XX:+ExitOnOutOfMemoryError`）もない（SEC-001/002）。 |
| 攻撃シナリオ | 攻撃者がアプリ内で任意コード実行に至った場合の被害が大きい。OOM後に中途半端に生き残ったプロセスがリクエストを失敗させ続ける。 |
| 再現手順 | `Dockerfile` を読むのみ。 |
| 修正案 | `RUN addgroup -S app && adduser -S app -G app` と `USER app`。`ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]`。ベースイメージは `@sha256:...` で固定する。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-017** |
| 重大度 | Low |
| 確度 | 要確認（Renderの `ipAllowList` の既定値） |
| 分類 | インフラ |
| 場所 | `reBack/render.yml:54-57` |
| 内容 | `databases:` に `ipAllowList` がない。未指定の場合、外部（インターネット）からDBへ接続できる可能性がある（パスワード認証のみ）。 |
| 攻撃シナリオ | 攻撃者1。DBのホスト名が漏れれば、パスワードの総当たりを外部から試せる。 |
| 再現手順 | 設定を読むのみ（Render上では未確認）。 |
| 修正案 | `ipAllowList: []`（内部接続のみ許可）を指定する。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-018** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | DoS / コスト |
| 場所 | `MonsterRateLimiter.java:44,57-72,127-137`（メモリ上のMap） |
| 内容 | IP単位の回数と、Geminiのキーごとの1日の呼び出し数（200回）は、すべてプロセスのメモリ上にある。Render無料枠はアイドル時にスリープし、再起動のたびに **すべて0に戻る**。OOM（SEC-001/002）で意図的に再起動させることもできる。 |
| 攻撃シナリオ | 攻撃者5。再起動を挟みながら、Geminiの日次上限を繰り返し超えて呼び出す。 |
| 再現手順 | コードの追跡（`HashMap` のみ。永続化なし）。 |
| 修正案 | Gemini側にも **プロジェクトの予算・クォータ上限** を設定する（コストの最終防衛線）。日次カウンタはDBに持つ。 |

| 項目 | 内容 |
| --- | --- |
| ID | **VAL-004** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | バリデーション（ステータス） |
| 場所 | `ReceiptTableRepository.java:203,218,270-274` |
| 内容 | パス変数 `{tableName}` は **すべての経路**（GET／DELETE）で `assertSafeTableName` を通り、SQLインジェクションは成立しない（良い点）。ただし形式が不正な値に **500 `UNSAFE_TABLE_NAME`（「安全でないテーブル名が生成されました」）** を返す。クライアントの入力ミスであり、400/404が適切。 |
| 攻撃シナリオ | 攻撃者4（管理者のブラウザ経由）。意味のある攻撃にはならないが、500が増える（VAL-001）。 |
| 再現手順 | `HttpPocTest.adminApiInputs`。結果: `POC|TABLENAME|receipt_ABCDEF000...(大文字) GET=500(UNSAFE_TABLE_NAME) DELETE=500`、`x -> 500`、`aaa...(300文字) -> 500`。`receipt_0'; DROP TABLE x;--` はJava HTTPクライアント側が拒否（`IllegalArgumentException`）。`..%2f..%2fetc`／`%00`／`%0a` はTomcatが400。 |
| 修正案 | `findReceipt`／`deleteReceipt` の前に `isSafe` を判定し、不正なら `RECEIPT_NOT_FOUND`（404）にする。 |

| 項目 | 内容 |
| --- | --- |
| ID | **VAL-005** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | バリデーション（数値の範囲） |
| 場所 | `MonsterCardGenerator.java:86-98,105-109`、`ReceiptItemData.java`、`ReceiptStructuredData.java` |
| 内容 | `totalAmount`・`unitPrice`・`amount`・`quantity` の範囲検査がない。負の合計（Gemini・管理者の入力）で、`fA = Math.min(total, 30000) / 30000.0` が負になり、能力値が **定義された範囲の外**（HP 200〜700）に出る。`itemSum` の `long` 加算はオーバーフローする。 |
| 攻撃シナリオ | 攻撃者3。画像内の文字で負の合計を出させると、HPが範囲外（`-214748145` など）のカードができる。対戦では最初の1発で負けるので、自分に不利なだけ（相手を有利にする手段にはならない）。 |
| 再現手順 | `LogicPocTest.cardGeneratorWithHostileNumbers`。結果: `CARD-TOTAL|total=-9223372036854775808 hp=-214748145 def=-214748332 power=-257697840`、`total=-30000 hp=158`（<200）、`CARD-OVERFLOW|hp=330 atk=80`（オーバーフローして合計が負）。 |
| 修正案 | `fA` などの要素を `Math.clamp(x, 0.0, 1.0)` に収める。金額は `|x| ≤ 10^9`（値引きで負の金額はありうるので、負の値は許し、範囲だけ制限する）、`Math.addExact` で加算する。 |

| 項目 | 内容 |
| --- | --- |
| ID | **VAL-006** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | バリデーション（型） |
| 場所 | `MonsterController.java:42-49`、`ReceiptSaveRequest.java`、`ReceiptService.java:55-74` |
| 内容 | JSONの型の変換が緩い。`{"index":"0"}` は `0`、`{"index":1.5}` は `1`、`lines:[1,2]` は `["1","2"]`、`{"username":123}` は文字列として受理される。空白だけの行（`["  "]`）はそのまま保存される。`lines` が文字列そのもの、`amount` が文字列だと500（VAL-001）。 |
| 攻撃シナリオ | 直接の攻撃にはならない。入力の厳密さが保証されず、将来のバグ（検証の抜け）の原因になる。 |
| 再現手順 | `POC|MONSTER-FLIP|{"index":1.5} -> 200`、`{"index":"0"} -> 409 ALREADY_FLIPPED`（`"0"` が `0` として解釈された）、`POC|SAVE|lines=[1,2] -> 200 OK`、`lines=['  '] -> 200 OK`。 |
| 修正案 | JacksonのScalar変換を無効にする（`MapperFeature.ALLOW_COERCION_OF_SCALARS`、`DeserializationFeature.ACCEPT_FLOAT_AS_INT` を無効化）。`lines` の各要素は `isBlank` を除外して数え、1つも残らなければ400。 |

| 項目 | 内容 |
| --- | --- |
| ID | **VAL-007** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | バリデーション（列挙値・AIの出力） |
| 場所 | `ReceiptService.java:55-74`、`GeminiReceiptAnalyzer.java:240-248,364-374`、`MonsterController.java` |
| 内容 | ①`storeCategory`・品目の`category`・`paymentMethod` に許可リストがなく、`<img src=x onerror=alert(1)>` のような文字列も保存・配信される（表示側が `esc()` するので無害）。②Geminiの出力（`structuredData`）は、`lines` の件数（1000）以外、**項目数・文字数・値を検証せず**ユーザー入力と同じ経路で使われる。③`purchasedAt` は年 `99999` も通る。 |
| 攻撃シナリオ | SEC-012・VAL-002と同じ。 |
| 再現手順 | `POC|SAVE|storeCategory=HTML -> 200 OK`、`POC|SAVE|purchasedAt 年99999 -> 200 OK`。 |
| 修正案 | 許可リストに丸める。日時は現実的な範囲（例: 2000年〜現在+1日）に制限する。Geminiの結果も、保存・カード生成の前に同じバリデータを通す。 |

| 項目 | 内容 |
| --- | --- |
| ID | **GEN-002** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | 重複ファイル / 保守 |
| 場所 | `reFront/app.js`・`select.js`・`login.js`・`admin-api.js`・`config.js`・`styles.css` ↔ `reBack/src/main/resources/static/admin/` |
| 内容 | `reFront/` の管理画面用JSは **どのHTMLからも読まれていない**（死んだコード）。`app.js` は文言が古く（`GEMINI_API_KEY` を指す）、`config.js` は `DEMO_URL`／`GAME_URL` の2行が多い。`smoke.test.mjs` は両者の同一性を要求しており、現在それが赤い（GEN-001）。片方だけ修正して対策が漏れるリスクがある。 |
| 攻撃シナリオ | 直接の攻撃はない。 |
| 再現手順 | `diff reFront/app.js reBack/src/main/resources/static/admin/app.js`（2か所）、`diff reFront/config.js ...`（2行）、その他は同一。`reFront/*.html` を検索しても `app.js` 等の参照はない。 |
| 修正案 | `reFront/` から使われていないJSを削除する（`index.html`・`index.js`・`config.js`・`theme/`・`styles.css` だけ残す）。または複製をやめてバックエンドのファイルを参照する。 |

| 項目 | 内容 |
| --- | --- |
| ID | **GEN-003** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | 例外処理 / 監視 |
| 場所 | `ApiExceptionHandler.java:69-72,32-40` |
| 内容 | 汎用ハンドラ・`DataAccessException` ハンドラのどちらも **ログを一切出さない**。500・503が起きても原因が残らず、攻撃や障害の検知ができない。 |
| 再現手順 | コードに `Logger` がない。 |
| 修正案 | 5xxは `LOGGER.error("...", e)`（スタックトレースはサーバーログのみ）、4xxは `LOGGER.debug`。ログにはユーザー入力をそのまま出さない（改行による偽ログ行を避けるため、出す場合は制御文字を除く）。今回の調査で、ユーザー入力・AIの出力をそのままログへ出す箇所は見つからなかった（第7章）。 |

| 項目 | 内容 |
| --- | --- |
| ID | **GEN-004** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | 性能 / スレッド・DB |
| 場所 | `MonsterRoomService.java:386-396`、`MonsterCardRepository.java:304-308`、`ReceiptTableRepository.java:183-192,194-200,251-258` |
| 内容 | ①`state` などの参照系リクエストでも、`enter()` が行ロック（`FOR UPDATE`）と `UPDATE` を行う（1〜2秒ごとのポーリングで書き込みが発生）。②`activeCardIds()` が毎回全件を走査する。③`ensureImageHashRegistry()` が毎回 `ALTER TABLE ... DROP NOT NULL`（強いロック）を実行する。④`findAllReceiptTables` が、レシートごとに `COUNT` を1回実行する（N+1）。 |
| 攻撃シナリオ | 攻撃者5。席トークンなしでも、有効なルームコードに対して失敗する要求がロックを取る（SEC-014）。 |
| 再現手順 | コードの追跡。 |
| 修正案 | 参照系は `SELECT`（ロックなし）にし、`last_seen` の更新は間引く。DDLは起動時の1回にする。 |

| 項目 | 内容 |
| --- | --- |
| ID | **GEN-005** |
| 重大度 | Low |
| 確度 | 確定 |
| 分類 | スレッド / 信頼性 |
| 場所 | `MonsterTaskRunner.java:15-27`、`MonsterRoomService.java:296-299` |
| 内容 | 非同期タスクのスレッドは2本、キューは200件で、あふれた場合は `DiscardPolicy` で **黙って破棄**する。`cpuTurn`（コンピュータ対戦の思考）が破棄されると、そのルームは `SELECTING` のまま進まなくなる（再試行する手段がない）。SEC-006のように2スレッドが塞がると、同じ状況になる。 |
| 再現手順 | コードの追跡。 |
| 修正案 | 破棄時にログを出し、CPUの手番は同期で処理するか、失敗時に再投入する。スレッド数・キュー長を設定値にする。 |

### Info

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-019** |
| 重大度 | Info |
| 確度 | 要確認 |
| 分類 | プライバシー |
| 場所 | `GeminiReceiptAnalyzer.java:105-168`、`static/**/*.html`・`theme.js` |
| 内容 | レシート画像を **EXIF（位置情報を含みうる）ごと** Geminiへ送る（サーバーには保存しない。保存するのは画像のSHA-256だけ）。無料枠のAPIキーでは、入力が製品改善に使われうる。また全ページが `fonts.googleapis.com` を読み込む（訪問者のIPがGoogleに渡る）。 |
| 修正案 | 有料枠（データを学習に使わない契約）を確認する。EXIFを送信前に取り除く（再エンコードする場合はSEC-002の上限を先に入れる）。 |
| 対応状況 | 利用者への告知は実施済み（入口ページ・対戦画面・解析画面）。キーが無料枠か有料枠かは管理者が決めるため、告知は無料枠を前提とした文面にした。有料枠に切り替えたら文面を直す。EXIF除去は未対応。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-020** |
| 重大度 | Info |
| 確度 | 確定 |
| 分類 | 認証 / 設定 |
| 場所 | `.env.example:9`、`SecurityConfig.java:27-29` |
| 内容 | ①`BCryptPasswordEncoder` は72バイトを超える入力を **黙って切り捨てる**（72バイトのハッシュに100バイトの入力で `matches=true`。PoC `BCRYPT|*`）。長いパスワードを設定しても先頭72バイトしか効かない。②`.env.example` は `SESSION_COOKIE_SECURE=false`（ローカル用）。そのままRenderに貼ると平文でもCookieが送られるが、`render.yml` は `"true"` を指定しており、`application.yml` の既定も `true`。 |
| 修正案 | ①パスワードの最大長を72バイトと文書化する。②`.env.example` にコメントを足す。 |

| 項目 | 内容 |
| --- | --- |
| ID | **SEC-021** |
| 重大度 | Info |
| 確度 | 確定 |
| 分類 | ローカル開発サーバー |
| 場所 | `reFront/server.mjs:24-40` |
| 内容 | パストラバーサルは防げている（`normalize` + `startsWith(ROOT)`。ROOTは末尾が `/` なので兄弟ディレクトリにも抜けない。`%2e%2e`、`..%2f`、`//`、`%00` はすべて404）。`localhost`（127.0.0.1）にだけバインドされ、外部からは届かない。ドットファイル（`.gitignore`）・`server.mjs`・`test/` は配信される（秘密情報なし）。`Host` ヘッダーを検証しないので、DNSリバインディングで他サイトからも読める（配信物は公開情報だけ）。 |
| 再現手順 | `review/poc/serve_mjs_probe.sh`、結果は `results_serve_mjs.txt`。 |

| 項目 | 内容 |
| --- | --- |
| ID | **GEN-006** |
| 重大度 | Info |
| 確度 | 確定 |
| 分類 | その他 |
| 内容 | ①`GET /error` を直接叩くと500（`BasicErrorController`）。②`MonsterRoomService.create()` はコードの重複確認の後に挿入するため、同時作成で一意制約違反（確率は極小）→ 500になりうる。③`MonsterCardService.backfillLegacyReceipts`（起動時）は **Geminiを呼ばない**（`MonsterCardGenerator.generate` の計算式のみ）ことを確認した。`render.yml` に `MONSTER_BACKFILL_ON_STARTUP` はなく既定の `true` だが、起動時に大量のGemini呼び出しは発生しない。失敗した個別のレシートは例外を握りつぶす（`skipped++`、ログなし）。 |

---

## 5. 入力項目ごとの検証状況

凡例: ○=十分、△=一部、×=なし

### 管理者API

| 入力項目 | 検証している場所 | 検証内容 | 不足 |
| --- | --- | --- | --- |
| `POST /api/auth/login` `username` | `LoginAttemptLimiter.key`（正規化のみ） | null→空文字、trim、小文字化 | × 長さ（SEC-001）、型違いは強制変換（VAL-006） |
| 同 `password` | なし（BCrypt照合） | null→401 | × 長さ。72バイト切り捨て（SEC-020） |
| 同 ボディ全体 | なし | — | × 不正JSON・`null`・空・Content-Type違いは500（VAL-001） |
| `GET/DELETE /api/receipts/{tableName}` | `ReceiptTableRepository.assertSafeTableName` | `receipt_[0-9a-f]{32}`（完全一致） | △ 不正は500（VAL-004）。SQLインジェクションは不可（○） |
| `POST /api/receipts/analyze` `file`（multipart） | `ReceiptUploadValidator` | 空・5MB・Content-Type（申告値） | △ マジックナンバーなし、縦横なし（SEC-015, SEC-002）。5MB超は500（VAL-001） |
| `POST /api/receipts/save` `sha256` | `ReceiptService.normalizeSha256` | 64桁の16進、小文字化、`reserveImageHash` 済みか（DBの更新結果） | ○ 未予約は409で文言が誤解を招く（軽微） |
| 同 `lines` | `ReceiptService.store` | null要素の除去、空なら400 | △ 件数・長さ・NUL・空白のみ（VAL-002, 003, 006） |
| 同 `lines`（個人情報） | `PersonalInfoSanitizer` | 正規表現で除去、残れば422 | △ すり抜け（SEC-004）、ReDoS（SEC-006） |
| 同 `structuredData`（null） | `ReceiptTableRepository.saveStructuredData` | nullは既定値 | ○ NullPointerExceptionにならない（確認済み） |
| 同 `storeName`／`branchName` | なし | — | × 255文字超は503（VAL-002） |
| 同 `storeCategory`／品目 `category` | なし | — | × 許可リストなし、50文字超は503（VAL-002, 007） |
| 同 `paymentMethod`／`receiptNumber` | なし（`paymentMethod` は除去のみ） | — | × 100文字超は503、`receiptNumber` は個人情報除去の対象外 |
| 同 `purchasedAt` | Jackson（形式のみ） | ISO形式 | △ 形式違いは500、範囲なし（VAL-001, 007） |
| 同 `totalAmount`／`unitPrice`／`amount` | なし | `Long` 型のみ | × 範囲・オーバーフロー（VAL-005）。文字列は500 |
| 同 `quantity` | DBの桁（`NUMERIC(12,3)`） | — | × 桁あふれは503（VAL-002） |
| 同 `items`（null要素・name null） | `saveStructuredData` | 読み飛ばす | ○ 500にならない（確認済み）。件数の上限なし（VAL-003） |
| 同 ボディ全体 | なし | — | × サイズ上限なし（VAL-003）。不正JSONは500 |

### モンスター対戦API

| 入力項目 | 検証している場所 | 検証内容 | 不足 |
| --- | --- | --- | --- |
| `{code}` | `MonsterRoomService.normalizeCode` | trim、大文字化、`[A-HJ-NP-Z2-9]{6}` | ○ |
| `X-Seat-Token` | `authorize` | SHA-256ハッシュ後に `MessageDigest.isEqual` | ○ 形式は検査しないが、ハッシュ比較なので問題なし |
| `flip.index` | `MonsterRoomService.flip` | 範囲（0〜フィールド数）、選択済み、手札上限 | △ 小数・文字列を強制変換（VAL-006）、不正JSONは500 |
| `select.cardId` | `MonsterRoomService.select` | 自分の手札か画像カードに含まれるか | ○ 相手のカードID・存在しないIDは400（確認済み） |
| `rematch.round` | `MonsterRoomService.rematch` | 現在のラウンドと一致したときだけ開始 | ○ |
| `image-card` `file` | `detectImageType` | 空・5MB・先頭バイト（JPEG/PNG） | △ 縦横なし（SEC-002）、認可前に受信（SEC-013）、5MB超は500 |
| `shuffle`／`field`／`battle`／`leave`／`state` | 状態・席の検査 | — | △ 回数制限なし（SEC-005, 014） |

### テスト（境界値・異常値）の不足

既存テストは、認可・CSRF・セッション・ロック・トークン・状態遷移・SVG・個人情報の基本ケースをよく押さえている（`AuthenticationIntegrationTest`、`ReceiptApiAuthorizationIntegrationTest`、`MonsterRoomIntegrationTest`、`LoginAttemptLimiterTest` など）。**足りないテスト**:

1. `ApiExceptionHandler`: 不正JSON・空ボディ・Content-Type違い・型違い・5MB超が400/415/413になること（VAL-001）。
2. `AuthController.login`: 長さ上限、`null` ボディ、25M文字の入力が（キーを保存せず）拒否されること（SEC-001）。
3. `LoginAttemptLimiter`: ロック中のエントリが追い出されないこと（SEC-008）。
4. `PersonalInfoSanitizer`: 全角ハイフン電話・全角数字のみ・`.` 区切り・中黒区切りのカード・「お客様番号」・「山田 太郎 様」・担当者名・全角郵便番号／メール（SEC-004）。ReDoS（2000個の空白、3万文字の英数字）が一定時間内に終わること（SEC-006）。
5. `MonsterSvgValidator`: コメント内の `<script>`、巨大な`width`、要素数・深さの上限（SEC-011）。
6. `ReceiptUploadValidator`／`detectImageType`: マジックナンバー違い、巨大な縦横（画像ボム）、破損した画像、JPEGの先頭だけが正しい別形式（SEC-002, 015）。
7. `ReceiptService.store` と `MonsterCardGenerator`: 負・極端な金額、`Long` のオーバーフロー、NUL文字、255文字超、`quantity` の桁あふれ（VAL-002, 005）。
8. `MonsterRateLimiter`: `X-Forwarded-For` の扱い（末尾のみ使う、複数値、空）、IPv6の丸め、Mapの上限（SEC-007）。
9. `ReceiptTableRepository`: 不正なテーブル名が404になること（VAL-004）。
10. ヘッダー: `Content-Security-Policy`・`Referrer-Policy`・HSTS の有無（SEC-009, 010）。

---

## 6. テストの実行結果

| コマンド | 結果 |
| --- | --- |
| `cd reBack && mvn test`（`reBack` のコピーで実行。DBはH2のPostgreSQLモード。`mvn -o`） | **136件中 成功132、エラー4、失敗0**。エラーはすべて `ReceiptControllerIntegrationTest`（`resetAnalyzer` の `DROP TABLE receipt_structured_summary` が外部キーで失敗）。ログ: `review/poc/mvn_test.log`。GEN-001 |
| `node --test reFront/test`（Node v22.22.2） | **起動できない**（`Cannot find module '/disk01/github/receipt-game/reFront/test'`。Node 22はディレクトリ指定に対応しない） |
| `node --test "reFront/test/*.test.mjs"` | **23件中 成功22、失敗1**。失敗は「reFront copies of admin scripts match the files served by Backend」（`app.js` の文言差）。ログ: `review/poc/node_test.log`。GEN-001, GEN-002 |
| PoC `LogicPocTest`（9件） | 実行成功（結果は `results_poc_output.txt`） |
| PoC `ImageBombPocTest`（1件、別JVMで `-Xmx128m`／`-Xmx256m`） | 実行成功（`OutOfMemoryError` を確認） |
| PoC `HttpPocTest`（8件、Tomcat起動・H2） | 実行成功 |
| PoC `PgPocTest`（1件、使い捨てのPostgreSQL 18） | 実行成功（`results_poc_postgres.txt`）。使い捨てコンテナ `review-poc-pg` は終了後に削除した（既存のDBコンテナには触れていない） |
| `reFront/server.mjs` の確認 | `serve_mjs_probe.sh`（`results_serve_mjs.txt`）。問題なし |

- 依存ライブラリの既知の脆弱性スキャン（OWASP Dependency-Check等）は **未実施**（ネットワーク上のDBが必要なため）。バージョン一覧は冒頭に記載。`google-genai` が引き込む `httpclient 4.5.14`・`okhttp 4.12.0` は旧系統なので、DependabotまたはOWASP Dependency-Checkで定期確認することを勧める。

---

## 7. 問題なしと確認できた項目

| 項目 | 確認した方法 |
| --- | --- |
| 匿名での管理者API到達 | 認可マトリクス40パターン（`MATRIX|*`）。`/api/**` の未定義パスは `denyAll`。パス細工（`//`、`;`、`%2e%2e`、大文字、`.json`、`%61pi`）も401/400/404 |
| `anyRequest().permitAll()` で意図せず公開されるもの | `/game`・`/demo`・`/theme`・`/admin/login*` のみ。`/admin/menu.html`・`upload.html`・`select.html`・`app.js`・`select.js` は未ログインで302。`/application.yml`・`/.env` は404 |
| CSRF（管理者API） | ログイン済みでトークンなし／誤り → 403、未ログインは401（認可で先に弾かれる）。`/api/monster/**` はCookieで認証しないためCSRF対象外で問題なし（`CSRF|*`） |
| ログインCSRF・ログアウト | トークンなしのログイン → 403。ログアウト後、旧Cookieは401。`XSRF-TOKEN` を破棄する |
| セッション固定 | 攻撃者が用意した `JSESSIONID` を送ってログイン → 新しいIDが発行され、旧IDは401（`FIXATION|*`）。`changeSessionId` は成功経路で常に実行される |
| Cookie属性 | `Secure; HttpOnly; SameSite=Lax`（セッション・CSRFとも）。`render.yml` は `SESSION_COOKIE_SECURE=true`。アプリの既定も `true` |
| ユーザー列挙 | ロック時の応答はユーザー名の有無で同一（429・同文言）。bcrypt照合時間も差がない（Spring Securityのダミー照合） |
| `/api/auth/session` の返却範囲 | 匿名は `{"authenticated":false}` のみ。管理者のときだけユーザー名 |
| SQLインジェクション | `ReceiptTableRepository` の文字列連結（INSERT／SELECT／DROP）は、`createReceiptTableAndInsert`（`generate()` で生成）・`findReceipt`・`deleteReceipt` のすべてで `assertSafeTableName`（完全一致の正規表現）を通る。DBから読んだ名前を再利用する `findAllReceiptTables` は `isSafe` で絞り込み、`MonsterCardRepository.receiptLines` も `isSafe` を検査。値はすべて `?` のプレースホルダー |
| 席トークン | `SecureRandom` 32バイト（256bit、URLセーフBase64で43文字）、DBにはSHA-256のみ保存、`MessageDigest.isEqual` で比較。応答・ログにトークンのハッシュは出ない |
| 席・ルームの分離 | トークンなし／誤り／別ルームの席で、`state`・`field`・`battle`・`shuffle`・`leave`・`flip`・`select`・`rematch` がすべて403。自席の権限で相手のカードID・存在しないIDを `select` → 400。応答にSHA-256・テーブル名は含まれない |
| 状態遷移と同時実行 | 5本の同時 `flip` → 手札は上限の3枚を超えない。両席の同時 `select` → 対戦は1回だけ。対戦後の `flip`・`select`・`shuffle` は409。`FOR UPDATE` による直列化が効いている |
| ルームコード | 6桁×32種。`join` は10回/分/IPで制限（12回中10回目まで404、以降429） |
| XSS（管理画面・ゲーム） | `static/admin/*.js` は `textContent`／`createElement`、`innerHTML` は定数のみ。`game.js` はサーバー由来の文字列をすべて `esc()` 経由。数値項目はサーバー側で`int`／`long`。`theme.js` の `?theme=` は数値化 |
| SVGの表示 | `<img src="data:image/svg+xml;charset=utf-8,` + `encodeURIComponent(svg)` のみ。`innerHTML` への直接挿入・新規タブ・ダウンロードはない |
| XXE・エンティティ展開 | `MonsterSvgValidator` は DOCTYPE を禁止、外部エンティティ無効、`FEATURE_SECURE_PROCESSING`。DOCTYPE／ENTITY／CDATAは拒否（PoC） |
| 秘密情報の混入 | `git grep` で `AIza…`・bcryptハッシュ・パスワード代入を検索（`doc/`・README除く）→ なし。追跡されている`.env`系は `.env.example` のみ。`.gitignore`／`.dockerignore` は `.env`・`*.log`・`target` を除外 |
| エラー応答 | スタックトレース・例外メッセージを返さない。公開側のGemini失敗は、設定の詳細（キー名など）を伏せた文言に変換（`MonsterRoomService.readReceipt`） |
| 起動時の設定チェック | `ADMIN_USERNAME`・`ADMIN_PASSWORD_HASH`・`DB_PASSWORD` は既定値なし。`GEMINI_API_DEFAULT` が空なら `@PostConstruct` で起動中止。BCrypt形式でないハッシュでは誰もログインできない（安全側） |
| 起動時のGemini呼び出し | `backfillLegacyReceipts` は計算式のみ。Geminiを呼ばない |
| ログ | ユーザー入力・Geminiの出力をそのまま出す箇所は見つからなかった（例外はクラス名のみ、`IP` は塩付きハッシュをメモリに持つだけ） |
| CORS | 設定なし。別オリジンからのプリフライトに `Access-Control-Allow-Origin` は付かない |
| `reFront/server.mjs` | パストラバーサルは不可、localhostにのみバインド（SEC-021） |

---

## 8. 修正の優先順位の提案

### すぐ直すべきもの（公開サービスの停止・個人情報に直結）

1. **SEC-001** ログインの入力長の上限とリミッターのキーの固定長化（数行で直る）
2. **SEC-002** 画像の縦横の上限検査（管理者・公開で共通化）と、Dockerfileの `-XX:+ExitOnOutOfMemoryError`（SEC-016の一部）
3. **VAL-001** `ApiExceptionHandler` に400/413/415のハンドラを追加（あわせて GEN-003 のログ出力）
4. **SEC-004** 個人情報除去の強化（NFKC正規化、数字の連続、「お客様番号」の順序）。**SEC-005** で公開項目を減らす（`receiptNumber`・`branchName`・時刻の丸め）
5. **SEC-006** 正規表現を線形にし、入力長を制限する

### リリース前に直すべきもの

6. **SEC-003** ロックアウト方式の見直し（IP+ユーザー名、バックオフ）と **SEC-008**（ロックを追い出さない）
7. **SEC-007** Render上で `X-Forwarded-For` の実際の形を確かめ、IP判定とIPv6の丸め、Mapの上限を直す。**SEC-010** `forward-headers-strategy` とHSTS
8. **VAL-002**・**VAL-003** 入力の長さ・文字・件数・ボディサイズの検証と、DBエラーの400/503の区別
9. **SEC-009** CSP・Referrer-Policy・Permissions-Policy
10. **SEC-013** 認可前の受信を避ける（`resolve-lazily` またはフィルター）。**SEC-014** 存在判定の統一
11. **GEN-001** テストを緑に戻す（`resetAnalyzer`、`node --test` の指定）。**SEC-017** `ipAllowList: []`
12. **SEC-018** Google側の予算・クォータ上限の設定（コストの最終防衛線）

### 余裕があれば

13. **SEC-011**（SVG検証の厳格化）、**SEC-012**（文字列の許可リスト）、**SEC-015**（アップロード検証の共通化）
14. **VAL-004〜007**（ステータス、数値範囲、JSONの厳密化、許可リスト）
15. **GEN-002**（`reFront/` の死んだ複製の削除）、**GEN-004**（DB負荷）、**GEN-005**（タスクの破棄）
16. **SEC-019〜021**・**GEN-006**（Info）、依存ライブラリの定期スキャンの導入
