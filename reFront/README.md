# フロントエンド（reFront）

レシート解析システムの利用者向けの静的ページです。HTML / CSS / JavaScriptで構成し、ビルドは不要です。

## ページ

| ファイル | 内容 |
| --- | --- |
| `index.html` / `index.js` | メニュー。デモ、レシート解析、保存済みレシートの確認への入口です。 |
| `demo.html` / `demo.js` | デモ。架空のレシート画像（`assets/demo-receipt.svg`）と固定データで解析結果を表示します。Gemini API・バックエンド・DBは呼び出しません。 |

ログイン、レシート解析、保存済みレシート一覧の画面は、バックエンドの `reBack/src/main/resources/static/admin/` から配信します。メニューの「レシートを解析」と「保存済みレシートを確認する」は、バックエンドのログイン画面へ移動します。

## ファイル

| ファイル | 内容 |
| --- | --- |
| `config.js` | 接続先URL（`window.APP_CONFIG`）の設定と、背景や見出しの装飾（金貨のSVG）の描画 |
| `styles.css` | 画面共通のスタイル |
| `app.js` / `select.js` / `login.js` / `admin-api.js` | 管理画面のスクリプト。バックエンド側の同名ファイルと同じ内容です。 |
| `assets/demo-receipt.svg` | デモ用の架空のレシート画像 |
| `test/smoke.test.mjs` | HTMLとスクリプトの内容を検査するテスト |

## 接続先

`config.js` は、ページを開いた場所によって接続先を切り替えます。

| 開き方 | バックエンド | 「メニューへ戻る」の移動先 |
| --- | --- | --- |
| ファイルとして開く、またはlocalhost / 127.0.0.1 | `http://localhost:8081` | `http://localhost:5500/index.html` |
| 上記以外 | `https://receipt-analysis-b8po.onrender.com` | `https://sr5msym6xbw3oil22im7.github.io/receipt-analysis/reFront/index.html` |

## ローカルでの利用

1. バックエンドとPostgreSQLを起動します（[reBack/README.md](../reBack/README.md) を参照）。
2. `index.html` をブラウザーで開きます。
3. デモはバックエンドなしで動作します。解析にはログインとGemini APIキーの入力が必要です。

## 解析画面の動作（app.js）

- JPEG / PNG画像、またはJPEG / PNGだけを含むZIPを、ファイル選択またはドラッグ＆ドロップで指定します。
- ZIPはブラウザーで展開し、中の画像を1枚ずつ解析APIへ送ります。JPEG / PNG以外のファイル、暗号化されたファイル、未対応の圧縮方式が含まれる場合は処理を中断します。
- 解析の要求は210秒、保存の要求は30秒で打ち切ります。解析中は15秒ごとに経過時間を表示します。
- すべての画像の解析が成功すると「PostgreSQLへ保存」ボタンが表示されます。
- Gemini APIキーの上限超過や無効などのエラーでは、APIキー欄にフォーカスし、キーを入れ替えて再度解析するよう案内します。
- 管理APIの呼び出しには `admin-api.js` の `adminFetch` を使い、POST・DELETEの要求にCSRFトークンを付けます。

## 管理画面スクリプトの同期

`app.js`、`select.js`、`login.js`、`admin-api.js` は、`reBack/src/main/resources/static/admin/` の同名ファイルと同じ内容に保ってください。ブラウザーに配信されるのはバックエンド側のファイルです。

## テスト

リポジトリ直下で実行します。

```sh
node --test reFront/test/smoke.test.mjs
```

画面の要素、デモが通信しないこと、Gemini APIキーをブラウザーのストレージに保存しないこと、管理画面スクリプトの内容が一致することなどを検査します。

## 変更時の注意

- HTMLから読み込むCSSとスクリプトのURLには `?v=` でバージョン文字列を付けています。内容を変更したら更新してください。スモークテストは `upload.html` が読み込む `app.js` のバージョン文字列を検査します。
- APIから受け取った文字列は `textContent` で表示してください。
