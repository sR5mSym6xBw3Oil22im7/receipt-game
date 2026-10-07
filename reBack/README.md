# バックエンド（reBack）

レシート解析システムのREST APIと管理画面です。Java 21、Spring Boot 4.1.0（Web / Security / JDBC）、PostgreSQL、Google Gen AI SDK（google-genai）を使用します。

## ディレクトリ構成

```
reBack/
├── pom.xml
├── Dockerfile
├── render.yml
├── .env.example
├── index.html                      ../reFront/index.html へ転送
├── src/main/java/com/example/receipt/
│   ├── ReceiptApplication.java
│   ├── config/                     SecurityConfig（認証・認可・CSRF・ログアウト）
│   ├── controller/                 AuthController, HealthController, ReceiptController, ApiExceptionHandler
│   ├── service/                    ReceiptService, GeminiReceiptAnalyzer, ReceiptUploadValidator,
│   │                               GeminiApiKeyPolicy, LoginAttemptLimiter
│   ├── repository/                 ReceiptTableRepository, ReceiptTableName
│   ├── dto/                        要求・応答のrecord
│   └── exception/                  ReceiptException
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   └── static/admin/               管理画面（login.html, upload.html, select.html とスクリプト）
└── src/test/                       JUnitテスト
```

## ローカルでの起動

```sh
export DB_PASSWORD='<ローカルDBのパスワード>'
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD_HASH='<BCrypt形式のハッシュ>'
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

- HTTP待受ポートの既定値は `8081` です。
- `local` プロファイルでは、CookieのSecure属性を無効にし、ログを `log/backend.log` に出力します。
- 起動後、`GET /api/health` が `{"status":"UP"}` を返します。

## 環境変数

| 変数 | 既定値 | 説明 |
| --- | --- | --- |
| `PORT` | `8081` | HTTP待受ポート |
| `DB_HOST` | `localhost` | PostgreSQLのホスト |
| `DB_PORT` | `5432` | PostgreSQLのポート |
| `DB_NAME` | `receipt_db` | データベース名 |
| `DB_USER` | `postgres` | DBユーザー |
| `DB_PASSWORD` | なし | DBパスワード。必須 |
| `ADMIN_USERNAME` | なし | 管理者のユーザーID。必須 |
| `ADMIN_PASSWORD_HASH` | なし | 管理者パスワードのBCryptハッシュ。必須 |
| `SESSION_COOKIE_SECURE` | `true` | セッションCookieとCSRF CookieのSecure属性 |
| `LOGIN_MAX_FAILURES` | `5` | ログインをロックするまでの連続失敗回数 |
| `LOGIN_LOCK_MINUTES` | `15` | ロックする時間（分） |

設定例は `.env.example` にあります。Geminiのモデルは `application.yml` の `gemini.receipt-model`（`gemini-3.5-flash-lite`）で指定します。Gemini APIキーはサーバーに設定せず、解析の要求ごとに受け取ります。

## API

| メソッド | パス | 認可 | 内容 |
| --- | --- | --- | --- |
| GET | `/api/health` | 公開 | 稼働確認 |
| GET | `/api/auth/csrf` | 公開 | CSRFトークンを返す |
| GET | `/api/auth/session` | 公開 | ログイン状態を返す |
| POST | `/api/auth/login` | 公開 | ログイン（JSON：`username`、`password`） |
| POST | `/api/auth/logout` | ログイン済み | ログアウト（成功時204） |
| POST | `/api/receipts/analyze` | 管理者 | multipart（`file`、`geminiApiKey`）の画像を解析。DBには登録しない |
| POST | `/api/receipts/save` | 管理者 | 解析結果（JSON：`lines`、`sha256`、`structuredData`）を保存 |
| GET | `/api/receipts` | 管理者 | 保存済みレシートの一覧 |
| GET | `/api/receipts/{tableName}` | 管理者 | 保存済みレシートの詳細 |
| DELETE | `/api/receipts/{tableName}` | 管理者 | 保存済みレシートの削除（成功時204） |

業務エラーは `code`、`message`、`timestamp` を含むJSONで返します。

## 認証・認可

- 管理者は `ADMIN_USERNAME` と `ADMIN_PASSWORD_HASH` で設定する1名で、権限はROLE_ADMINです。
- `/api/receipts` 配下は、HTTPメソッドを問わずROLE_ADMINが必要です。未ログインは401、管理者以外は403を返します。
- 上の表にない `/api` 配下のパスは、管理者を含めてすべて拒否します。
- POST・DELETEの要求には、`X-XSRF-TOKEN` ヘッダーでCSRFトークンを送る必要があります。
- ログインに成功すると、セッションIDを変更し、ログイン前のCSRFトークンを破棄します。
- 同じユーザーIDでログインに続けて失敗し、回数が `LOGIN_MAX_FAILURES` に達すると、`LOGIN_LOCK_MINUTES` の間はHTTP 429で拒否します。照合中の要求も回数に含めます。記録はアプリのメモリ上にだけ保持します。
- 管理画面（ログイン画面と、その画面が使うファイルを除く `/admin/` 配下）は、未ログインの場合ログイン画面へリダイレクトします。

## 解析と保存

- 解析できる画像はJPEG / PNGで、最大5,242,880バイト（5 MiB）です。
- Geminiには応答のJSONスキーマを指定し、行ごとの文字列（`lines`）と構造化データ（`structuredData`）を受け取ります。Geminiへの要求のタイムアウトは180秒です。
- 画像のSHA-256で重複を判定し、保存済みの画像は409（`DUPLICATE_RECEIPT_IMAGE`）を返します。
- 保存時は、レシートごとに `receipt_` に32桁の16進数を付けた名前のテーブルを作成し、行番号と文字列を登録します。
- 構造化データは `receipt_structured_summary` と `receipt_structured_item`、画像のSHA-256は `receipt_image_hash_registry` に保存します。
- これらのテーブルはアプリが実行時に作成します。

## テスト

```sh
mvn test
```

テストではH2データベース（PostgreSQL互換モード）を使うため、PostgreSQLは不要です。

## Docker / Render

`Dockerfile` はMavenでJARをビルドし、Java 21のJREイメージで実行します（ポート8081）。

`render.yml` には、Web Service `receipt-analysis-b8po` とPostgreSQL `receipt-analysis-psl-service` が定義されています。DB接続用の環境変数はデータベースから設定され、`ADMIN_USERNAME` と `ADMIN_PASSWORD_HASH` は値を手動で設定する項目（`sync: false`）です。サービス名を変える場合は、`reFront/config.js` の接続先も変更してください。

## 管理画面のスクリプト

`src/main/resources/static/admin/` の `app.js`、`select.js`、`login.js`、`admin-api.js` は、`reFront/` に同じ内容のファイルがあります。変更するときは両方を更新してください。`reFront/test/smoke.test.mjs` が内容の一致を検査します。
