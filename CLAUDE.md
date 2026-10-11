# CLAUDE.md

レシート画像をGoogle Geminiで解析してPostgreSQLに保存し、保存済みレシートからモンスターカードを作って対戦するWebシステム。詳細は [README.md](README.md)、[reBack/README.md](reBack/README.md)、[reFront/README.md](reFront/README.md)、`doc/`（特に `doc/32_coding_and_development_guide.html`）を参照。

## 作業ルール

- **返信は日本語で書く。** コードのコメントも既存の書き方に合わせて日本語。
- サービス（PostgreSQL、バックエンド、フロントエンド）を再起動・停止する前に、実際に何で動いているかを確認する（`docker ps`、`ps`、`/proc/<pid>/cgroup`）。systemdだと決めつけない。
  - このプロジェクトのDBはDockerコンテナ `receipt-game-db`（`postgres:16-alpine`、ポート55432→5432）。ホストのsystemdの `postgresql.service` は無関係。再起動は `docker restart receipt-game-db`。
- コミット・プッシュはユーザーが頼んだときだけ行う。`main` 上ならブランチを切ってから。
- Gemini APIキー、管理者のパスワードハッシュ、DB接続情報をリポジトリに含めない。`reBack/.env` はGit管理外。

## 構成

| パス | 内容 |
| --- | --- |
| `reBack/` | Java 21 / Spring Boot 4.1.0 のREST API、管理画面（`static/admin/`）、モンスター対戦（`/game/`）、テスト、Dockerfile、render.yml |
| `reFront/` | メニューの静的ページ（ビルド不要）、`server.mjs`（ローカル用、ポート5051固定）、`theme/` |
| `doc/` | 設計書（HTML）、デザインモック |
| `review/` | セキュリティレビューなどの記録 |

## コマンド

```sh
# バックエンド起動（reBack/.env が必要。http://localhost:8081）
cd reBack && mvn spring-boot:run -Dspring-boot.run.profiles=local

# フロントエンド起動（リポジトリ直下。http://localhost:5051/index.html）
node reFront/server.mjs

# テスト（バックエンドはH2を使うのでPostgreSQL不要）
cd reBack && mvn test
node --test reFront/test/smoke.test.mjs   # リポジトリ直下で実行
```

## 同期が必要なファイル（片方だけ直さない）

テストが内容の一致を検査する。

- `reBack/src/main/resources/static/admin/` の `app.js`、`select.js`、`login.js`、`admin-api.js` ⇔ `reFront/` の同名ファイル
- `reBack/src/main/resources/static/theme/` ⇔ `reFront/theme/`（元は `reBack` 側）

## コーディング規約

- `.editorconfig`：UTF-8、改行LF、ファイル末尾に改行。
- HTMLから読み込むCSS・スクリプトのURLには `?v=` でバージョン文字列を付けている。内容を変えたら更新する（スモークテストが `upload.html` の `app.js` のバージョンを検査）。
- APIから受け取った文字列は `textContent` で表示する（`innerHTML` を使わない）。
- 管理APIの呼び出しは `admin-api.js` の `adminFetch` を使い、POST・DELETEにはCSRFトークン（`X-XSRF-TOKEN`）を付ける。
- 画面の動きはCSSのtransition／animationのみ。`prefers-reduced-motion` で止まるようにする。
- `styles.css` は日替わりテーマ導入後も変更しない方針。テーマは `theme/base.css` と `theme/tNN.css` で上書きする。

## 仕様上のルール

### 認証・認可・セキュリティ

- 管理者は `ADMIN_USERNAME` と `ADMIN_PASSWORD_HASH`（BCrypt）で設定する1名（ROLE_ADMIN）。
- `/api/receipts` 配下はHTTPメソッドを問わずROLE_ADMINのみ（未ログイン401、管理者以外403）。READMEの表にない `/api` 配下は管理者でも拒否。
- ログイン失敗が `LOGIN_MAX_FAILURES`（5）回に達すると `LOGIN_LOCK_MINUTES`（15分）はHTTP 429。記録はメモリ上のみ。
- ログイン成功時にセッションIDを変更し、ログイン前のCSRFトークンを破棄する。
- 本番はHTTPS、`SESSION_COOKIE_SECURE` は既定 `true`（`local` プロファイルのみ無効）。
- 新しいAPIを足すときは `SecurityConfig` の認可設定とREADMEのAPI表を更新する。

### レシート解析・保存

- 解析できるのはJPEG / PNG（最大5 MiB）。JPEG / PNGだけのZIPはブラウザーで展開して1枚ずつ解析。
- 「解析」ではDBに登録しない。確認後に「PostgreSQLへ保存」で保存する。
- 画像ファイルそのものは保存しない。SHA-256（重複判定）、行ごとの文字列、構造化データを保存する。SHA-256が一致する画像は409（`DUPLICATE_RECEIPT_IMAGE`）。
- レシートごとに `receipt_` + 32桁16進数のテーブルを作る。他に `receipt_structured_summary`、`receipt_structured_item`、`receipt_image_hash_registry`。テーブルはアプリが実行時に作成する。
- Geminiの応答にはJSONスキーマを指定する。タイムアウトはバックエンド180秒、解析画面の要求210秒、保存30秒。
- Gemini APIキーは `GEMINI_API_DEFAULT`（必須、未設定ならバックエンドは起動しない）と用途別 `GEMINI_API_ANALYZE` / `_MONSTER` / `_PLAYER1` / `_PLAYER2`。画面から入力させず、DBにも保存しない。
  - 解析：`ANALYZE` → `DEFAULT`。モンスターのカード生成：`MONSTER` → `DEFAULT` → 計算式の値と代替イラスト。
- Geminiのモデルは `application.yml` の `gemini.receipt-model`。

### モンスターレシート対戦

- 要件は `doc/34_monster_receipt_battle_requirements.html`。対戦画面は `/game/index.html`（ログイン不要）、APIは `/api/monster/rooms`（席トークン `X-Seat-Token`）。勝敗はサーバーで計算する。
- 連動：解析で下書き、保存で有効、削除でカードも削除。
- 保存前に個人情報（氏名・電話番号・会員番号・住所・メール・カード番号）を検出して「（非表示）」に置換し、取り除けなければ保存しない。
- 起動時にカードのない保存済みレシートへカードを作る（`MONSTER_BACKFILL_ON_STARTUP`）。レシートの自動削除はしない。
- 対戦には有効なカードが2枚以上必要。
- 環境変数：`MONSTER_WAIT_SECONDS`（既定90）、`MONSTER_AI_ILLUSTRATION`、`MONSTER_TRUST_FORWARDED_FOR`（プロキシ背後で true）、`MONSTER_GEMINI_DAILY_LIMIT`（既定200）。

### 日替わりデザイン

- 10種類のテーマが毎日3:00（JST）に順番に切り替わる。日付だけで決まる。`?theme=1`〜`?theme=10` で確認用に固定できる（保存しない）。

## デプロイ

- バックエンドとPostgreSQLはRender（`reBack/render.yml`）。`sync: false` の環境変数は手動で設定する。サービス名を変えたら `reFront/config.js` の接続先も変える。
- `reFront/config.js` は localhost 以外で開かれると本番の接続先を使う。
