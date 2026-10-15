# フロントエンド（reFront）

モンスターレシート対戦のメニューと動画視聴ページです。HTML / CSS / JavaScriptだけで構成し、ビルドは不要です。GitHub Pagesで配信します。

対戦画面（`/game/`）、デモ（`/demo/`）、管理画面（`/admin/`）は、バックエンドの `reBack/src/main/resources/static/` から配信します。メニューのリンクはそれらを開きます。

## ページ

| ファイル | 内容 |
| --- | --- |
| `index.html` / `index.js` | メニュー。モンスターレシート対戦・デモ・管理者ログイン（この順）と、動画4本へのリンク |
| `video/show/guide6min.html` / `guide3min.html` | 操作ガイド（標準版・簡易版。`video/manual/` の mp4） |
| `video/show/pv1.html` / `pv2.html` | プロモーション映像（`video/pv/pv01.mp4`・`pv02.mp4`） |

## ファイル

| ファイル | 内容 |
| --- | --- |
| `config.js` | 接続先URL（`window.APP_CONFIG`）。`reBack/src/main/resources/static/admin/config.js` と、`DEMO_URL`・`GAME_URL` の2行以外は同じ内容 |
| `direct-access-guard.js` | メニュー以外のページを直接開いたら `index.html` へ移動させる。各ページの `<head>` の先頭で読み込む |
| `styles.css` | 共通スタイル（`static/admin/styles.css` と同じ内容）。テーマ導入後は変更しない方針 |
| `theme/` | 日替わりテーマ（`theme.js`、`base.css`、`t01.css`〜`t10.css`）。元は `reBack/src/main/resources/static/theme/` |
| `server.mjs` | ローカル確認用の静的ファイルサーバー（http://localhost:5051、ポート固定） |
| `test/smoke.test.mjs` | HTMLとスクリプトの内容を検査するテスト（管理画面・対戦画面のファイルも対象） |

## 接続先

`config.js` は、ページを開いた場所で接続先を切り替えます。

| 開き方 | バックエンド | 「メニューへ戻る」の移動先 |
| --- | --- | --- |
| ファイルとして開く、または localhost / 127.0.0.1 | `http://localhost:8081` | `http://localhost:5051/index.html` |
| 上記以外 | `https://receipt-game.onrender.com` | `https://sr5msym6xbw3oil22im7.github.io/receipt-game/reFront/index.html` |

## ローカルでの利用

1. バックエンドとPostgreSQLを起動します（[reBack/README.md](../reBack/README.md) を参照）。
2. リポジトリ直下で `node reFront/server.mjs` を実行し、http://localhost:5051/index.html を開きます。
3. 対戦とデモはログイン不要です。レシートの解析には、管理者のログインと、バックエンドの `.env` の `GEMINI_API_DEFAULT`（または `GEMINI_API_ANALYZE`）が必要です。

`server.mjs` は `localhost:5051` などのHostだけを受け付け、GET・HEAD以外、ドットで始まるファイル、不正なエンコードを拒否します。

## 日替わりデザイン

- 10種類のテーマ（案01 葡萄サロン／02 トッピング注文／03 白磁と洋紅／04 トラットリア図鑑／05 純喫茶ナイト／06 黄昏ゾーン／07 モノクロ索引／08 食券機／09 泡のパフェ／10 レシート路線図）が、毎日3:00（日本時間）に順番に切り替わります。日付だけで決まり、開いたままの画面も3:00に切り替わります。
- URLに `?theme=1`〜`?theme=10` を付けると、そのテーマで表示します（保存しません）。
- 各ページの `theme/theme.js` が `theme/base.css` と `theme/tNN.css` を読み込み、`styles.css` を上書きします。
- 動きはCSSのtransition／animationだけで、`prefers-reduced-motion` の環境では止まります。
- テーマを変えるときは `reBack` 側を直し、`reFront/theme/` に同じ内容をコピーしてください（テストで一致を検査）。

## 直接アクセスの扱い

- メニュー（`index.html`）以外のURLを直接開くと、`index.html` へ移動します。同じサイトの `reFront/` 配下のページからのリンクで開いた場合だけ表示します。
- `reFront/`（`index.html` を省いたURL）は `index.html` へ移動します。
- `server.mjs` は、同じサイトからのリンクでないページ移動と、存在しないURLへのページ移動を `/index.html` へ302で転送します。
- GitHub Pagesでは、存在しないURLはリポジトリ直下の `404.html` が `reFront/index.html` へ転送します。

## テスト

リポジトリ直下で実行します。ポート5051を使うので、起動中の `server.mjs` は止めてください。

```sh
node --test reFront/test/smoke.test.mjs
```

画面の要素、解析画面にGemini APIキーの入力欄がなく送信もしないこと、`config.js`・テーマの一致、ZIPの制限、`server.mjs` の挙動、演出が `prefers-reduced-motion` で止まることなどを検査します（31件）。

## 変更時の注意

- HTMLから読み込むCSSとスクリプトのURLには `?v=` でバージョン文字列を付けています。内容を変えたら更新してください。
- APIから受け取った文字列は `textContent` で表示してください。
- 動画（`video/` の mp4）のエンコードは1ファイルずつ順番に行ってください。
