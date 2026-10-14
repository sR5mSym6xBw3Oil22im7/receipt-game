# バックエンド（reBack）

モンスターレシート対戦のREST APIと、管理画面・対戦画面・デモの配信を行います。Java 21、Spring Boot 4.1.0（Web / Security / Validation / JDBC）、PostgreSQL、Google Gen AI SDK（google-genai 1.67.0）を使います。

## ディレクトリ構成

```
reBack/
├── pom.xml
├── Dockerfile
├── render.yml
├── .env.example
├── index.html                       ../reFront/index.html へ転送
├── src/main/java/com/example/receipt/
│   ├── ReceiptApplication.java
│   ├── config/                      SecurityConfig, DirectAccessRedirectFilter, GeminiApiKeys
│   ├── controller/                  AuthController, ReceiptController, HealthController, ApiExceptionHandler
│   ├── service/                     ReceiptService, GeminiReceiptAnalyzer, ReceiptUploadValidator, ImageInspector,
│   │                                LoginAttemptLimiter, GeminiApiKeyPolicy, ReceiptBatchImporter
│   ├── repository/                  ReceiptTableRepository, ReceiptTableName
│   ├── monster/                     対戦API・ルーム・カード・生成AI・回数制限
│   │   └── engine/                  カード生成・対戦・乱数・SVG・個人情報の除外（DBに依存しない計算）
│   ├── dto/                         要求・応答のrecord
│   └── exception/                   ReceiptException
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   └── static/
│       ├── admin/                   管理画面（login, menu, upload, select）
│       ├── game/                    モンスターレシート対戦（/game/index.html）
│       ├── demo/                    デモ（/demo/index.html）
│       └── theme/                   日替わりテーマ（reFront/theme/ の元）
└── src/test/                        JUnitテスト（18クラス）
```

## 起動

```sh
cp .env.example .env   # 値を編集する
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

- 起動したディレクトリ（`reBack/`）の `.env` を読み込みます（`spring.config.import: optional:file:.env[.properties]`）。OSの環境変数があればそちらが優先です。
- `GEMINI_API_DEFAULT` が空の場合は起動しません。
- 待受ポートは `8081` です。`GET /api/health` が `{"status":"UP"}` を返します。
- `local` プロファイルでは、CookieのSecure属性を無効にし、ログを `log/backend.log` に出力し、メニューのURLを `http://localhost:5051/index.html` にします。

## 環境変数

| 変数 | 既定値 | 説明 |
| --- | --- | --- |
| `PORT` | `8081` | HTTP待受ポート |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` | `localhost` / `5432` / `receipt_db` / `postgres` | PostgreSQLの接続先 |
| `DB_PASSWORD` | なし | DBのパスワード。必須 |
| `ADMIN_USERNAME` | なし | 管理者のユーザーID。必須 |
| `ADMIN_PASSWORD_HASH` | なし | 管理者パスワードのBCryptハッシュ。必須 |
| `SESSION_COOKIE_SECURE` | `true` | セッションCookieとCSRF CookieのSecure属性 |
| `LOGIN_MAX_FAILURES` | `5` | ログインをロックするまでの失敗回数 |
| `LOGIN_LOCK_MINUTES` | `15` | ロックする時間（分） |
| `GEMINI_API_DEFAULT` | なし | Gemini APIの既定キー。必須 |
| `GEMINI_API_ANALYZE` | `GEMINI_API_DEFAULT` | レシート解析用。失敗したら `GEMINI_API_DEFAULT` で読み直す |
| `GEMINI_API_MONSTER` | `GEMINI_API_DEFAULT` | 保存時のカードのパラメータ・イラスト用。失敗したら `GEMINI_API_DEFAULT`、それも失敗したら計算式の値と代替イラスト |
| `GEMINI_API_PLAYER1` / `GEMINI_API_PLAYER2` | `GEMINI_API_DEFAULT` | 対戦画面で画像からカードを作るとき（席ごと）。`PLAYER2` はコンピュータのカード選択にも使う |
| `GEMINI_MONSTER_MODEL` | `gemini-3.5-flash-lite` | 対戦関連で使うモデル |
| `MONSTER_WAIT_SECONDS` | `90` | P2を待つ秒数。過ぎたらコンピュータ対戦 |
| `MONSTER_ROOM_TTL_MINUTES` | `120` | ルームの有効期限（アクセスのたびに延長） |
| `MONSTER_AI_ILLUSTRATION` | `true` | 生成AIでカードのパラメータとイラストを作るか |
| `MONSTER_TRUST_FORWARDED_FOR` | `false` | `X-Forwarded-For` の末尾を利用者のIPとして使うか（Renderなどプロキシの背後で `true`） |
| `MONSTER_GEMINI_DAILY_LIMIT` | `200` | 対戦関連のGemini呼び出しの、キーごとの1日の上限 |
| `MONSTER_BACKFILL_ON_STARTUP` | `true` | 起動時に、カードのない保存済みレシートを再点検してカードを作るか |

レシート解析のモデルは `application.yml` の `gemini.receipt-model`（`gemini-3.5-flash-lite`）です。

## API

| メソッド | パス | 認可 | 内容 |
| --- | --- | --- | --- |
| GET | `/api/health` | 公開 | 稼働確認 |
| GET | `/api/auth/csrf` | 公開 | CSRFトークンを返す |
| GET | `/api/auth/session` | 公開 | ログイン状態を返す |
| POST | `/api/auth/login` | 公開 | ログイン（JSON：`username`、`password`） |
| POST | `/api/auth/logout` | ログイン済み | ログアウト（204） |
| POST | `/api/receipts/analyze` | 管理者 | multipart（`file`）の画像を解析。DBには登録しない |
| POST | `/api/receipts/save` | 管理者 | 解析結果（JSON：`lines`、`sha256`、`structuredData`）を保存 |
| GET | `/api/receipts` | 管理者 | 保存済みレシートの一覧 |
| GET | `/api/receipts/{tableName}` | 管理者 | 保存済みレシートの詳細 |
| DELETE | `/api/receipts/{tableName}` | 管理者 | 削除（204）。構造化データ・画像ハッシュ・カードも削除 |
| POST | `/api/monster/rooms` | 公開 | ルーム作成（P1の席トークンを返す） |
| POST | `/api/monster/rooms/{code}/join` | 公開 | ルーム参加（P2の席トークンを返す） |
| GET | `/api/monster/rooms/{code}` | 席トークン | ルームの状態 |
| GET | `/api/monster/rooms/{code}/field` | 席トークン | 配られたレシート・手札・画像カード |
| POST | `/api/monster/rooms/{code}/shuffle` | 席トークン | 配り直し |
| POST | `/api/monster/rooms/{code}/flip` | 席トークン | レシートをめくる（JSON：`index`） |
| POST | `/api/monster/rooms/{code}/image-card` | 席トークン | 画像からカードを作る（multipart：`file`） |
| POST | `/api/monster/rooms/{code}/select` | 席トークン | カードを決定（JSON：`cardId`） |
| GET | `/api/monster/rooms/{code}/battle` | 席トークン | 対戦結果 |
| POST | `/api/monster/rooms/{code}/rematch` | 席トークン | 再戦（JSON：`round`） |
| POST | `/api/monster/rooms/{code}/leave` | 席トークン | 退出（204） |

- 業務エラーは `code`、`message`、`timestamp` を含むJSONで返します。
- 席トークンは `X-Seat-Token` ヘッダーで送ります。対戦APIはCookieを使わないためCSRFの対象外で、応答は `Cache-Control: no-store` です。
- 詳細は [doc/15_api_specification.html](../doc/15_api_specification.html) を参照してください。

## 認証・認可

- 管理者は `ADMIN_USERNAME` と `ADMIN_PASSWORD_HASH` で設定する1名で、権限はROLE_ADMINです。
- `/api/receipts` 配下は、HTTPメソッドを問わずROLE_ADMINが必要です。未ログインは401、管理者以外は403です。
- 上の表にない `/api` 配下のパスは、管理者でも拒否します。新しいAPIを足すときは `SecurityConfig` の認可設定とこの表を更新してください。
- POST・DELETEには `X-XSRF-TOKEN` ヘッダーでCSRFトークンが必要です（対戦APIを除く）。
- ログインに成功すると、セッションIDを変更し、ログイン前のCSRFトークンを破棄します。
- 同じユーザーIDで続けて失敗し、回数が `LOGIN_MAX_FAILURES` に達すると、`LOGIN_LOCK_MINUTES` の間はHTTP 429で拒否します。照合中の要求も回数に含めます。記録はメモリ上だけです。
- 未ログインで `/admin/` 配下（ログイン画面と、その画面が使うファイルを除く）を開くと、ログイン画面へリダイレクトします。
- 応答にはCSP、Referrer-Policy（no-referrer）、Permissions-Policy などのヘッダーを付けます。

## 直接アクセスの転送

`/admin/` 以外のページ（`/game/`、`/demo/`、存在しないURLなど）をアドレスバー・ブックマーク・メニュー以外のサイトのリンクで開くと、`app.frontend.menu-url` へ302で転送します（`DirectAccessRedirectFilter`、Spring Securityより前）。同じサイトからの移動と、メニューのオリジンからのリンクは表示します。API呼び出し、CSS・スクリプト・画像の読み込み、`/api/health` は対象外です。

## 解析と保存

- 解析できる画像はJPEG / PNG（先頭バイトで判定）で、5,242,880バイト以下、幅×高さが約2,500万画素以下です。
- Geminiには応答のJSONスキーマを指定します。タイムアウトは180秒です。横向き・逆さまの画像は回転して読み直し、行数が多い方を使います。
- 画像のSHA-256で重複を判定し、保存済みの画像は409（`DUPLICATE_RECEIPT_IMAGE`）です。
- 保存時は、個人情報を置き換えてから、レシートごとに `receipt_` ＋ 32桁の16進数のテーブルを作って行を登録し、構造化データを `receipt_structured_summary`・`receipt_structured_item` に、画像のSHA-256を `receipt_image_hash_registry` に保存します。個人情報を取り除けなければ422（`PERSONAL_INFO_CHECK_FAILED`）です。
- テーブルはアプリが実行時に作ります（対戦用の `monster_card`、`monster_room`、`monster_play`、`monster_battle` も同様）。

## モンスターカードと対戦

- 解析の応答を返した後、別スレッドでカードの下書きを作ります。保存時に生成AIでパラメータとイラスト（SVG）を作って有効にします。保存されなかった下書きは24時間で破棄します。
- 生成AIの出力は範囲・カタカナ・許可リストで検証し、使えなければ計算式の値と代替イラストを使います。
- 対戦の勝敗は `MonsterBattleEngine` がシード付き乱数で計算します。計算はデモの `static/demo/engine.js` と同じで、`MonsterEngineParityTest` が一致を検査します。どちらかを変えたら両方を直してください。
- IPごとの回数制限：ルーム作成5回／10分、参加10回／1分、画像からのカード作成10回／1日。

## 一括処理

起動引数で指定したときだけ動き、終わるとアプリを終了します。

```sh
# フォルダの画像を1件ずつ解析・保存する（結果は log/batch-import.tsv）
mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments="--app.batch-import.dir=/path/to/receipts"

# 保存済みレシートのカードを生成AIで作り直す（結果は log/monster-batch.tsv）
mvn spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.arguments="--app.monster-batch.enabled=true"
```

## テスト

```sh
mvn test
```

H2（PostgreSQL互換モード）を使うため、PostgreSQLは不要です。Geminiは呼び出しません。

## Docker / Render

- `Dockerfile` はMavenでJARをビルドし、Java 21のJREイメージで、root以外のユーザーとして実行します（ポート8081、`-XX:MaxRAMPercentage=70`、`-XX:+ExitOnOutOfMemoryError`）。
- `render.yml` には、Web Service `receipt-game`（Docker、Free、ヘルスチェック `/api/health`）とPostgreSQL `receipt-analysis-psl-service`（Free、外部接続なし）を定義しています。
- DB接続の環境変数はデータベースから設定されます。`ADMIN_USERNAME`、`ADMIN_PASSWORD_HASH`、`GEMINI_API_DEFAULT`、`GEMINI_API_PLAYER1`、`GEMINI_API_PLAYER2`、`GEMINI_API_MONSTER`、`GEMINI_API_ANALYZE` は手動で設定する項目（`sync: false`）です。
- サービス名を変える場合は、`reFront/config.js`、`static/admin/config.js` の接続先も変えてください。

## 同期が必要なファイル

片方だけ直さないでください。

- `static/admin/config.js` ⇔ `reFront/config.js`（`reFront` 側の `DEMO_URL`・`GAME_URL` の2行を除く。テストで検査）
- `static/theme/` ⇔ `reFront/theme/`（元は `reBack` 側。テストで検査）
- `static/admin/styles.css` ⇔ `reFront/styles.css`（現在は同じ内容。テストでは検査しない）
