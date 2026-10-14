# モンスターレシート対戦

レシート画像をGoogle Geminiで解析してPostgreSQLに保存し、保存したレシートからモンスターカードを作って対戦するWebシステムです。管理者がレシートを登録し、プレイヤーはログインせずに、ルームコードを共有して2人で対戦します。相手が来なければコンピュータと対戦します。

- メニュー：https://sr5msym6xbw3oil22im7.github.io/receipt-game/reFront/index.html
- バックエンド：https://receipt-game.onrender.com

## 機能

| 機能 | 利用者 | 内容 |
| --- | --- | --- |
| モンスターレシート対戦 | だれでも | ルームを作る（P1）か、6桁のコードで参加する（P2）。配られたレシートをめくってカードを手札にし（最大3枚）、1枚を決めて対戦する。自分のレシート画像からカードを作ることもできる。勝敗はサーバーで計算する |
| デモ | だれでも | 架空のレシートで、カード作成から対戦までを体験する。Gemini API・バックエンドのAPI・DBは使わない |
| 操作ガイド・プロモーション映像 | だれでも | メニューから動画を再生する |
| レシート解析 | 管理者 | JPEG / PNG画像を解析し、行ごとの文字列と構造化データ（店舗名、購入日時、合計金額、商品など）を得る。JPEG / PNGだけのZIPは、ブラウザーで展開して1枚ずつ解析する |
| 保存 | 管理者 | 解析結果を確認してから保存する。保存前に個人情報を「（非表示）」に置き換え、モンスターカードを有効にする |
| 一覧・詳細・削除 | 管理者 | 保存済みレシートを確認・削除する。削除するとカードも消える |
| 日替わりデザイン | だれでも | 10種類のテーマが、毎日3:00（日本時間）に順番に切り替わる |

「解析」だけではDBに登録しません。結果を確認して「データベースへ保存」を押したときに保存します。

## データの扱い

- レシート画像そのものは保存しません。重複判定に使うSHA-256、読み取った文字列、構造化データを保存します。同じ画像は409（`DUPLICATE_RECEIPT_IMAGE`）になります。
- 保存前に、氏名・電話番号・会員番号・住所・メールアドレス・カード番号を検出して「（非表示）」に置き換えます。取り除けない場合は保存しません。
- Gemini APIキーはバックエンドの環境変数で設定します。画面から入力させず、DBにも保存しません。

## 構成

| パス | 内容 |
| --- | --- |
| `reFront/` | メニュー、動画視聴ページ、日替わりテーマ、ローカル用サーバー（`server.mjs`）。GitHub Pagesで配信。詳細は [reFront/README.md](reFront/README.md) |
| `reBack/` | Java 21 / Spring Boot のREST API、管理画面（`/admin/`）、対戦画面（`/game/`）、デモ（`/demo/`）、テスト、Dockerfile、render.yml。詳細は [reBack/README.md](reBack/README.md) |
| `doc/` | 開発文書（要件定義〜運用）。[doc/index.html](doc/index.html) から開く |
| `review/` | セキュリティレビューの記録 |
| `index.html` / `404.html` | GitHub Pagesで `reFront/index.html` へ転送するページ |

## ローカルでの起動

必要なもの：Java 21、Maven、PostgreSQL、Node.js、Gemini APIキー。

1. PostgreSQLにデータベースを用意します（既定は `localhost:5432/receipt_db`、ユーザー `postgres`）。テーブルはアプリが実行時に作ります。
2. `reBack/.env.example` を `reBack/.env` にコピーし、`DB_PASSWORD`、`ADMIN_USERNAME`、`ADMIN_PASSWORD_HASH`（BCrypt）、`GEMINI_API_DEFAULT` を設定して、バックエンドを起動します（http://localhost:8081）。

   ```sh
   cd reBack
   cp .env.example .env
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

3. 別のターミナルで、リポジトリ直下からフロントエンドを起動し、http://localhost:5051/index.html を開きます。

   ```sh
   node reFront/server.mjs
   ```

環境変数の一覧は [reBack/README.md](reBack/README.md) にあります。

## テスト

```sh
# バックエンド（H2を使うのでPostgreSQLは不要）
cd reBack
mvn test

# フロントエンド（リポジトリ直下で実行。ポート5051を使うので、起動中の server.mjs は止めておく）
node --test reFront/test/smoke.test.mjs
```

## 公開環境

- フロントエンドはGitHub Pages、バックエンドとPostgreSQLはRender（`reBack/render.yml`）で動かします。
- `reFront/config.js` は、localhost以外で開かれたときに本番の接続先を使います。Renderのサービス名を変えた場合は、接続先も変えてください。

## 注意事項

- Gemini APIキー、管理者のパスワードハッシュ、DB接続情報をリポジトリに含めないでください。`reBack/.env` はGitの管理外です。
- 本番はHTTPSで動かし、`SESSION_COOKIE_SECURE` を有効にしてください（既定値は `true`）。
- RenderのFreeプランは、停止からの起動に時間がかかります。
