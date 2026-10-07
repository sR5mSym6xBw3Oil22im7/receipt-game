# レシート解析システム

レシート画像をGoogle Gemini APIで解析し、読み取った文字列を確認してからPostgreSQLへ保存するWebシステムです。Gemini APIを呼び出さずに画面の流れを確認できるデモもあります。

## 機能

| 機能 | 利用者 | 内容 |
| --- | --- | --- |
| デモ | だれでも | 架空のレシート画像と固定データで解析結果を表示します。Gemini API・バックエンド・DBは呼び出しません。 |
| ログイン | 管理者 | ユーザーIDとパスワードでログインします。 |
| レシート解析 | 管理者 | JPEG / PNG画像をGeminiで解析し、行ごとの文字列と構造化データ（店舗名、購入日時、合計金額、商品など）を返します。JPEG / PNGだけを含むZIPは、ブラウザーで展開して1枚ずつ解析します。 |
| 保存 | 管理者 | 解析結果をPostgreSQLへ保存します。同じ画像（SHA-256が一致）は再登録できません。 |
| 一覧・詳細・削除 | 管理者 | 保存済みレシートの一覧、行ごとの文字列の表示、削除を行います。 |

「解析」だけではDBに登録しません。結果を確認して「PostgreSQLへ保存」を押したときに保存します。

## データの扱い

- 画像ファイルそのものは保存しません。重複判定に使うSHA-256、読み取った文字列、構造化データを保存します。
- Gemini APIキーは解析のたびに画面で入力し、サーバー設定やDBには保存しません。
- 解析・保存・一覧・詳細・削除のAPIは、バックエンドのSpring Securityで管理者（ROLE_ADMIN）だけに制限しています。

## 構成

| パス | 内容 |
| --- | --- |
| `reFront/` | メニューとデモの静的ページ（HTML / CSS / JavaScript）。詳細は [reFront/README.md](reFront/README.md) |
| `reBack/` | Java 21 / Spring Boot のREST API、管理画面（ログイン・解析・一覧）、テスト、Dockerfile、render.yml。詳細は [reBack/README.md](reBack/README.md) |
| `index.html` | `reFront/index.html` へ転送するページ |

管理画面のHTMLはバックエンド（`reBack/src/main/resources/static/admin/`）から配信します。メニューの「レシートを解析」「保存済みレシートを確認する」は、バックエンドのログイン画面へ移動します。

## ローカルでの起動

必要なもの：Java 21、Maven、PostgreSQL。フロントエンドのテストにはNode.jsも必要です。

1. PostgreSQLにデータベースを用意します（既定値は `localhost:5432/receipt_db`、ユーザー `postgres`）。テーブルはアプリが実行時に作成します。
2. 環境変数を設定してバックエンドを起動します。`ADMIN_PASSWORD_HASH` にはBCrypt形式のハッシュを設定します。

   ```sh
   export DB_PASSWORD='<ローカルDBのパスワード>'
   export ADMIN_USERNAME=admin
   export ADMIN_PASSWORD_HASH='<BCrypt形式のハッシュ>'
   cd reBack
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

3. `reFront/index.html` をブラウザーで開きます。ファイルとして開いた場合やlocalhostで開いた場合、接続先は `http://localhost:8081` になります。

環境変数の一覧は [reBack/README.md](reBack/README.md) を参照してください。

## テスト

```sh
# バックエンド（テスト用にH2データベースを使用）
cd reBack
mvn test

# フロントエンドのスモークテスト（リポジトリ直下で実行）
node --test reFront/test/smoke.test.mjs
```

## 公開環境

`reFront/config.js` は、localhost以外で開かれた場合に次の接続先を使います。

- バックエンド：`https://receipt-analysis-b8po.onrender.com`
- フロントエンド：`https://sr5msym6xbw3oil22im7.github.io/receipt-analysis/reFront/index.html`

バックエンドとPostgreSQLのRender向け構成は `reBack/render.yml` に定義されています。

## 注意事項

- 本番ではHTTPSを使い、`SESSION_COOKIE_SECURE` を有効にしてください（既定値は `true`）。
- Gemini APIキー、管理者のパスワードハッシュ、DB接続情報をリポジトリに含めないでください。`.env` はGitの管理対象外です。
