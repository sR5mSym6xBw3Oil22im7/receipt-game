# reFront（フロントエンド）

メニューの静的ページと、ローカル確認用のサーバーです。ビルドは不要です。全体の説明は [../README.md](../README.md) を参照してください。

## 内容

| パス | 内容 |
| --- | --- |
| `index.html` / `index.js` / `styles.css` | メニュー（対戦・デモ・管理者ログイン・動画4本へのリンク） |
| `config.js` | 接続先の切替。localhost（`file:` 含む）ではバックエンド `http://localhost:8081`、それ以外では本番 `https://receipt-game.onrender.com` |
| `direct-access-guard.js` | メニュー以外のページを直接開いたらメニューへ移動させる |
| `theme/` | 日替わりテーマ（`theme.js`、`base.css`、`t01.css`〜`t10.css`） |
| `video/manual/` | 操作ガイド動画（標準版 `guide6min.mp4`、簡易版 `guide3min.mp4`） |
| `video/pv/` | プロモーション映像（`pv01.mp4`、`pv02.mp4`） |
| `video/show/` | 動画再生ページ（`guide6min.html`、`guide3min.html`、`pv1.html`、`pv2.html`） |
| `server.mjs` | ローカル用の静的サーバー（`http://localhost:5051`、ポート固定） |
| `test/smoke.test.mjs` | スモークテスト（`node:test`、31件） |

対戦（`/game/`）・デモ（`/demo/`）・管理画面（`/admin/`）はバックエンド（`reBack/`）が配信します。メニューのリンクは `config.js` の接続先を使います。

## ローカルで動かす

```sh
node reFront/server.mjs        # リポジトリ直下で実行。http://localhost:5051/index.html
```

- ポートは5051固定です（バックエンドの「メニューへ戻る」の移動先と合わせています）。既に5051で別のプロセスが動いていると、起動できず、テストにも影響します。
- 許可するHostは `localhost`・`127.0.0.1`・`[::1]`（5051）だけです（DNSリバインディング対策）。GET/HEAD以外は405、`.` で始まるファイルは配信しません。
- メニュー以外へのページ移動（アドレスバー入力など）は、同じサイトのページからのリンクでなければメニューへ302転送します。

## 日替わりデザイン

10種類のテーマが毎日3:00（日本時間）に順番に切り替わります。日付だけで決まります。確認用に `?theme=1`〜`?theme=10` で固定できます（保存しません）。テーマは `theme/base.css` と `theme/tNN.css` で上書きし、`styles.css` は変更しません。

## 開発ルール

- HTMLから読み込むCSS・スクリプトのURLには `?v=` でバージョンを付け、内容を変えたら更新します。
- APIから受け取った文字列は `textContent` で表示します（`innerHTML` を使わない）。
- 画面の動きはCSSのtransition/animationのみ。`prefers-reduced-motion` で止まるようにします。
- 同期が必要なファイル：`theme/` は `reBack/src/main/resources/static/theme/` と同内容にします（元は `reBack` 側）。管理画面JS（`app.js` `select.js` `login.js` `admin-api.js`）も `reBack/…/static/admin/` と同期が必要です。
- 動画（`video/` のmp4）のエンコードは、必ず1ファイルずつ順番に実行します。ffmpegなどを複数同時に走らせないでください。

## テスト

```sh
node --test reFront/test/smoke.test.mjs      # リポジトリ直下で実行
```

管理画面JS・HTMLの整合、テーマ、演出（`prefers-reduced-motion`）、直接アクセス転送、`server.mjs` の拒否動作などを検査します。2026-10-15時点で31件すべて成功です（`server.mjs` のテストは、Node 24 の `fetch` が `Sec-Fetch-Mode` を上書きするため `node:http` で送る形に修正）。詳細は [../doc/29_test_result_report.html](../doc/29_test_result_report.html)。

## デプロイ

GitHub Pagesで配信します。ルートの `index.html` が `reFront/index.html` へ転送し、`404.html` が存在しないURLをメニューへ転送します。バックエンドのRenderサービス名を変えたら `config.js` の `backendBaseUrl` を更新してください。
