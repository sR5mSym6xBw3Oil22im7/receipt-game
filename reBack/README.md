# reBack（バックエンド）

モンスターレシート対戦のREST API、管理画面、対戦画面、デモ画面を配信するSpring Bootアプリケーションです。全体の説明は [../README.md](../README.md) を参照してください。

| 項目 | 内容 |
| --- | --- |
| 言語・FW | Java 21 / Spring Boot 4.1.0（Spring Security 7.1.0、Spring Framework 7.0.8、Tomcat 11） |
| アーティファクト | `receipt-backend-0.0.1-SNAPSHOT`（`com.example.receipt`） |
| DB | PostgreSQL（テストはH2） |
| AI | `google-genai` 1.67.0（`gemini-3.5-flash-lite`） |
| ポート | `PORT`（既定 8081） |

## 設計・実装の見どころ

- 勝敗とカード生成はサーバーで計算し、相手のカードは対戦開始まで返さない（`monster/engine/`）。
- Geminiが使えない・応答が不正なときは、計算式の値と代替イラストへ自動で切り替える。SVGは許可リストで検証する。
- `/api/**` は既定で拒否し、認可を書いたものだけ公開する（`SecurityConfig`）。CSRF、ログイン失敗ロック、アップロードの型・画素数検査、IP別のレート制限を備える。
- 保存前に個人情報を除去し、画像そのものは保存しない。
- テスト180件はH2で動き、Geminiを呼ばない。

## 起動

```sh
cp .env.example .env     # 値を設定する（.env はGit管理外。リポジトリに含めない）
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

`local` プロファイルは、Cookieの `Secure` 属性を無効にし、メニューURLを `http://localhost:5051/index.html` にします。`.env` は起動ディレクトリ（`reBack/`）から読み込まれ、OSの環境変数が優先されます。

## 環境変数

| 名前 | 必須 | 既定 | 説明 |
| --- | --- | --- | --- |
| `GEMINI_API_DEFAULT` | **必須** | — | 既定のGemini APIキー。未設定だと起動しない |
| `GEMINI_API_ANALYZE` | 任意 | DEFAULT | レシート解析用。失敗時はDEFAULTで再試行 |
| `GEMINI_API_MONSTER` | 任意 | DEFAULT | カード生成用 |
| `GEMINI_API_PLAYER1` / `GEMINI_API_PLAYER2` | 任意 | DEFAULT | 席ごとの画像カード作成用（PLAYER2はCPUの選択にも使用） |
| `ADMIN_USERNAME` | 必須 | — | 管理者ID |
| `ADMIN_PASSWORD_HASH` | 必須 | — | 管理者パスワードのBCryptハッシュ |
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USER` | — | localhost / 5432 / receipt_db / postgres | DB接続先 |
| `DB_PASSWORD` | 必須 | — | DBパスワード |
| `PORT` | 任意 | 8081 | ポート |
| `SESSION_COOKIE_SECURE` | 任意 | true | セッション/CSRF Cookieの `Secure`（ローカルのHTTPのみfalse） |
| `LOGIN_MAX_FAILURES` / `LOGIN_LOCK_MINUTES` | 任意 | 5 / 15 | ログイン失敗でのロック |
| `MONSTER_WAIT_SECONDS` | 任意 | 90 | 対人の待機秒数（超えるとCPU対戦） |
| `MONSTER_ROOM_TTL_MINUTES` | 任意 | 120 | 操作がないルームの失効 |
| `MONSTER_AI_ILLUSTRATION` | 任意 | true | AIによるカード/イラスト生成 |
| `MONSTER_TRUST_FORWARDED_FOR` | 任意 | false | プロキシ背後で true（Renderで設定済み） |
| `MONSTER_GEMINI_DAILY_LIMIT` | 任意 | 200 | キー別の1日のGemini呼出し上限 |
| `MONSTER_BACKFILL_ON_STARTUP` | 任意 | true | 起動時に、カードのない保存済みレシートへカードを作る |
| `GEMINI_MONSTER_MODEL` | 任意 | gemini-3.5-flash-lite | カード生成のモデル |

Geminiのキーは画面から入力させず、DBにも保存しません。解析は `ANALYZE` → `DEFAULT`、カード生成は `MONSTER` → `DEFAULT` → 計算式の値と代替イラストの順に使います。

## API

| Method | Path | 認証 | 用途 |
| --- | --- | --- | --- |
| GET | `/api/health` | 不要 | 稼働確認 |
| GET | `/api/auth/csrf` | 不要 | CSRFトークン取得 |
| GET | `/api/auth/session` | 不要 | ログイン状態 |
| POST | `/api/auth/login` | 不要（CSRF必須） | ログイン（5回失敗で15分429） |
| POST | `/api/auth/logout` | ログイン済み | ログアウト（204） |
| POST | `/api/receipts/analyze` | 管理者 | 画像解析（multipart `file`。DBには登録しない） |
| POST | `/api/receipts/save` | 管理者 | 解析結果の保存 |
| GET | `/api/receipts` | 管理者 | 保存済み一覧 |
| GET | `/api/receipts/{tableName}` | 管理者 | 詳細 |
| DELETE | `/api/receipts/{tableName}` | 管理者 | 削除（カードも削除） |
| POST | `/api/monster/rooms` | 不要 | ルーム作成 |
| POST | `/api/monster/rooms/{code}/join` | 不要 | ルーム参加 |
| GET | `/api/monster/rooms/{code}` | 席トークン | 状態 |
| GET | `/api/monster/rooms/{code}/field` | 席トークン | 場・手札 |
| POST | `/api/monster/rooms/{code}/shuffle` | 席トークン | シャッフル |
| POST | `/api/monster/rooms/{code}/flip` | 席トークン | レシートをめくる |
| POST | `/api/monster/rooms/{code}/image-card` | 席トークン | 画像からカード作成 |
| POST | `/api/monster/rooms/{code}/select` | 席トークン | カード決定 |
| GET | `/api/monster/rooms/{code}/battle` | 席トークン | 対戦結果 |
| POST | `/api/monster/rooms/{code}/rematch` | 席トークン | 再戦 |
| POST | `/api/monster/rooms/{code}/leave` | 席トークン | 退出 |

- 席トークンは `X-Seat-Token` ヘッダー。`/api/monster/**` はCookieを使わないためCSRFの対象外です。
- `/api/receipts` 配下はHTTPメソッドを問わず管理者のみ（未ログイン401、管理者以外403）。POST・DELETEは `X-XSRF-TOKEN` が必要です。
- 上の表にない `/api` 配下は、管理者でも拒否します。新しいAPIを足すときは `SecurityConfig` の認可設定とこの表を更新してください。
- リクエスト/レスポンスの詳細とエラーコードは [../doc/15_api_specification.html](../doc/15_api_specification.html)。

## 画面（静的ファイル）

| パス | 内容 |
| --- | --- |
| `/admin/login.html` `menu.html` `upload.html` `select.html` | 管理画面（`/admin/**` は管理者のみ。ログイン関連の静的ファイルのみ公開） |
| `/game/index.html` | モンスターレシート対戦（ログイン不要） |
| `/demo/index.html` | デモ（架空のレシート。サーバー保存なし） |
| `/theme/` | 日替わりテーマ（`reFront/theme/` に同内容を置く） |

## 構成

```
src/main/java/com/example/receipt/
  controller/   Auth・Receipt・Health・例外処理
  config/       SecurityConfig、DirectAccessRedirectFilter、GeminiApiKeys
  service/      ReceiptService、GeminiReceiptAnalyzer、ReceiptUploadValidator、ImageInspector、LoginAttemptLimiter、ReceiptBatchImporter
  repository/   ReceiptTableRepository、ReceiptTableName
  dto/          入出力・入力検証
  monster/      ルーム進行・カード連動・レート制限・Gemini呼出し
  monster/engine/  カード生成、対戦エンジン、個人情報除去、SVG検証（純粋な計算）
src/main/resources/  application*.yml、static/（admin・game・demo・theme）
src/test/            JUnit（H2を使用）
```

## データベース

テーブルはアプリが実行時に作成します（`CREATE TABLE IF NOT EXISTS`）。

- レシートごとに `receipt_` + 32桁16進数のテーブル（行番号と文字列）
- `receipt_structured_summary`、`receipt_structured_item`、`receipt_image_hash_registry`
- `monster_card`、`monster_room`、`monster_play`、`monster_battle`

画像ファイルそのものは保存しません。SHA-256（重複判定）、行ごとの文字列、構造化データ、カードを保存します。同じ画像は409（`DUPLICATE_RECEIPT_IMAGE`）。詳細は [../doc/14_database_design.html](../doc/14_database_design.html)。

## テスト

```sh
mvn test     # 180件。H2を使うのでPostgreSQL不要。Geminiは呼ばない設定
```

認可・CSRF・入力検証・ルーム進行・エンジン（デモ画面 `engine.js` との一致）・個人情報除去・SVG検証などを含みます。結果は [../doc/29_test_result_report.html](../doc/29_test_result_report.html)。

## Dockerとデプロイ

```sh
docker build -t receipt-game ./        # reBack/ で実行
```

- `Dockerfile`：Maven（Temurin 21）でビルドし、`eclipse-temurin:21-jre-alpine` を非rootで実行します。メモリ不足時は終了して再起動させます（`-XX:+ExitOnOutOfMemoryError`）。
- `render.yml`：Webサービス `receipt-game` とPostgreSQL `receipt-analysis-psl-service`（外部接続不可）。ヘルスチェックは `/api/health`。`sync: false` の環境変数（管理者・Geminiのキー）はRenderで手動設定します。
- サービス名を変えたら `reFront/config.js` の接続先も変更してください。

## ログ

`log/backend.log`（日次でZIPにローテーション）。APIキー・画像本文・個人情報は出力しない方針です。
