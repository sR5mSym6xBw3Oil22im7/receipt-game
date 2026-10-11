# PoC の実行方法

`reBack/` と `reFront/` は変更しないため、PoC は **reBack のコピー** の中で実行する。

```bash
# 1. reBack を作業用ディレクトリへコピー（target/ と log/ は除く）
WORK=$(mktemp -d)
rsync -a --exclude target --exclude log reBack/ "$WORK/reBack/"

# 2. PoC のテストコードを重ねる
cp -r review/poc/src/test/java/. "$WORK/reBack/src/test/java/"

# 3. 実行（依存は ~/.m2 にある前提で -o）。結果は標準出力の "POC|ID|..." 行
cd "$WORK/reBack"
mvn -o test -Dtest='LogicPocTest,ImageBombPocTest,HttpPocTest' -Dsurefire.failIfNoSpecifiedTests=false

# 4. PostgreSQL 固有の確認（NUL文字など）。使い捨てのPostgresを別名・別ポートで起動する
docker run --rm -d --name review-poc-pg -p 127.0.0.1:55432:5432 -e POSTGRES_PASSWORD=poc postgres:18
mvn -o test -Dtest=PgPocTest -DargLine="-Dpoc.pg=true" -Dsurefire.failIfNoSpecifiedTests=false
docker rm -f review-poc-pg
```

| ファイル | 内容 |
| --- | --- |
| `src/test/java/poc/LogicPocTest.java` | SVG検証、個人情報除去（すり抜け・ReDoS）、カード生成、ログイン試行制限（純ロジック） |
| `src/test/java/com/example/receipt/service/ImageBombPocTest.java` | 画像ボム（17KBのPNG）を `-Xmx128m` / `-Xmx256m` の別JVMで `rotateClockwise` に渡す |
| `src/test/java/poc/HttpPocTest.java` | 実際にTomcatを起動（H2）して HTTP で確認。認可・CSRF・セッション・入力・対戦API・レート制限・ヘッダー |
| `src/test/java/poc/PgPocTest.java` | PostgreSQL 固有の挙動（NUL文字、桁あふれ） |
| `results_poc_output.txt` | 上記（Pg以外）の実行結果（`POC|` 行のみ） |
| `results_poc_postgres.txt` | PgPocTest の実行結果 |
| `mvn_test.log` | 既存テスト `mvn test` の結果（reBack のコピーで実行） |
| `node_test.log` | 既存テスト `node --test "reFront/test/*.test.mjs"` の結果 |
| `serve_mjs_probe.sh` / `results_serve_mjs.txt` | `reFront/server.mjs` のパストラバーサル確認 |
| `curl_examples.sh` | 起動中のバックエンドに対する curl 手順（**ローカル専用**。未実行） |

注意: PoC の assert は「現状の挙動の記録」で、脆弱性が直ってもテストは落ちない（出力の `POC|` 行が変わる）。
