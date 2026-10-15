# モンスターレシート対戦（Monster Receipt Battle）

レシート画像をGoogle Geminiで解析してPostgreSQLに保存し、保存済みレシートからモンスターカードを作って対戦するWebシステムです。

- 管理者がレシート画像（JPEG/PNG、またはそれだけのZIP）を解析し、内容を確認してから保存します。
- 保存されたレシートが、世界に一枚のモンスターカードになります（同じレシート画像からは、必ず同じ名前・能力値のカード）。
- プレイヤーはログイン不要。ルームコードで2人対戦、90秒で相手が来なければコンピューターと対戦します。勝敗はサーバーで計算します。

## この作品について

卒業ポートフォリオとして個人で制作した作品で、就職活動の応募用に公開しています（開発期間：2026-10-07〜2026-10-15）。企画・要件定義から設計、実装、テスト、公開、運用の文書化までを一通り行いました。

- 見てほしい点：サーバーで完結する対戦ロジック、AI（Gemini）が使えないときの計算式への切り替え、認可・CSRF・個人情報除去などのセキュリティ設計、自動テスト（バックエンド180件・フロント31件）。
- 短時間で見る場合：このREADME → [企画書](doc/01_project_concept.html) → [技術選定・アピールポイント](doc/02_proposal.html) → [基本設計書](doc/09_basic_design.html) → [テスト結果報告書](doc/29_test_result_report.html)。
- すぐ試す：デモ画面（`/demo/index.html`）はログイン不要で、架空のレシートによるカード生成と対戦を体験できます。
- 実装の補助にAIコーディング支援（Claude Code）を併用しました。要件の決定と結果の確認は本人が行っています（[体制・AI活用方針](doc/07_organization.html)）。

| 項目 | 内容 |
| --- | --- |
| バージョン | 0.0.1-SNAPSHOT |
| バックエンド | Java 21 / Spring Boot 4.1.0 / Spring Security 7.1.0 |
| データベース | PostgreSQL（本番はRender、ローカルはDockerの `postgres:16-alpine`） |
| AI | Google Gemini（既定モデル `gemini-3.5-flash-lite`） |
| フロントエンド | ビルド不要の静的HTML/CSS/JavaScript |

## リポジトリ構成

| パス | 内容 |
| --- | --- |
| [reBack/](reBack/README.md) | REST API、管理画面（`static/admin/`）、対戦（`static/game/`）、デモ（`static/demo/`）、テスト、`Dockerfile`、`render.yml` |
| [reFront/](reFront/README.md) | メニューの静的ページ、`server.mjs`（ローカル用、ポート5051固定）、`theme/`、`video/` |
| [doc/](doc/index.html) | 開発ドキュメント（HTML、56点。目次は `doc/index.html`） |
| `review/` | セキュリティレビューの記録とPoC |
| `index.html` / `404.html` | GitHub Pages用。メニュー（`reFront/index.html`）へ転送します |
| `CLAUDE.md` | 開発時の作業ルール |

## クイックスタート（ローカル）

必要なもの：JDK 21、Maven 3.9、Node.js、Docker（ローカルDB用）、Gemini APIキー。

```sh
# 1. PostgreSQL（例）。ホストのポート55432→コンテナの5432
docker run -d --name receipt-game-db -e POSTGRES_PASSWORD=<任意のパスワード> -e POSTGRES_DB=receipt_db -p 55432:5432 postgres:16-alpine

# 2. 設定ファイルを作る（値は各自で設定。.env はGit管理外）
cp reBack/.env.example reBack/.env

# 3. バックエンド（http://localhost:8081）
cd reBack && mvn spring-boot:run -Dspring-boot.run.profiles=local

# 4. フロントエンド（別ターミナル、リポジトリ直下。http://localhost:5051/index.html）
node reFront/server.mjs
```

`GEMINI_API_DEFAULT` は必須です（未設定だとバックエンドは起動しません）。環境変数の一覧は [reBack/README.md](reBack/README.md#環境変数) を参照してください。

> サービス（PostgreSQL・バックエンド・フロントエンド）を再起動・停止する前に、実際に何で動いているかを確認してください（`docker ps`、`ps`）。このプロジェクトのDBはDockerコンテナ `receipt-game-db` で、ホストの `postgresql.service` とは無関係です。

## 画面とURL

| 画面 | URL（ローカル） | 認証 |
| --- | --- | --- |
| メニュー | `http://localhost:5051/index.html` | 不要 |
| モンスターレシート対戦 | `http://localhost:8081/game/index.html` | 不要 |
| デモ（架空のレシート） | `http://localhost:8081/demo/index.html` | 不要 |
| 管理者ログイン → レシート解析・保存・一覧 | `http://localhost:8081/admin/login.html` | 管理者 |

メニュー以外のページを直接開くと、メニューへ転送されます（`/admin/` を除く）。

## テスト

```sh
cd reBack && mvn test                        # H2を使うのでPostgreSQL不要（180件）
node --test reFront/test/smoke.test.mjs      # リポジトリ直下で実行（31件）
```

2026-10-15時点の結果：バックエンド180件、フロント31件ともすべて成功（フロントは初回に1件失敗したが、原因はテストの書き方で、修正済み。詳細は [doc/29](doc/29_test_result_report.html)・[doc/30](doc/30_defect_list.html)）。

## 同期が必要なファイル（片方だけ直さない）

テストが内容の一致を検査します。

- `reBack/src/main/resources/static/admin/` の `app.js`・`select.js`・`login.js`・`admin-api.js` ⇔ `reFront/` の同名ファイル
- `reBack/src/main/resources/static/theme/` ⇔ `reFront/theme/`（元は `reBack` 側）

## セキュリティ上の注意

- Gemini APIキー、管理者のパスワードハッシュ、DB接続情報をリポジトリに含めないでください。`reBack/.env` はGit管理外です。
- 管理者は `ADMIN_USERNAME` と `ADMIN_PASSWORD_HASH`（BCrypt）で設定する1名です。`/api/receipts` 配下は管理者のみ、READMEのAPI表にない `/api` 配下は管理者でも拒否されます。
- 保存前に個人情報（氏名・電話番号・会員番号・住所・メール・カード番号）を「（非表示）」に置換します。取り除けなければ保存しません。
- 対戦の結果画面では、両者の元レシートの内容が相手に表示されます（[doc/51](doc/51_privacy_materials.html)）。

## デプロイ

- バックエンドとPostgreSQL：Render（[reBack/render.yml](reBack/render.yml)）。`sync: false` の環境変数は手動で設定します。
- フロントエンド：GitHub Pages。`reFront/config.js` は localhost 以外で開かれると本番の接続先を使います。Renderのサービス名を変えたら `reFront/config.js` の接続先も変えてください。
- 手順は [doc/37](doc/37_release_plan.html)・[doc/38](doc/38_infrastructure.html)。

## ドキュメント

[doc/index.html](doc/index.html) を開いてください。主なもの：

| 目的 | 文書 |
| --- | --- |
| 仕様を知る | [要件定義書](doc/04_requirements_definition.html)、[ゲーム仕様書](doc/05_game_design_document.html)、[API仕様書](doc/15_api_specification.html)、[DB設計書](doc/14_database_design.html) |
| 開発する | [開発環境構築](doc/21_dev_environment.html)、[コーディング規約](doc/20_coding_standards.html)、[詳細設計書](doc/10_detailed_design.html) |
| 運用する | [運用設計書](doc/44_operations_design.html)、[障害対応](doc/46_incident_response.html)、[環境変数一覧](doc/39_env_secrets.html) |
| 全体を探す | [ドキュメント一覧と成果物の対応表](doc/55_document_map.html) |

実施していない事項（負荷試験・第三者診断・実機ブラウザー確認など）は、各文書で「未実施」と明記しています。個人開発のため、契約・金額・署名などは扱いません。
