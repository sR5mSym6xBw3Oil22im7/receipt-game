#!/usr/bin/env bash
# reFront/server.mjs のパストラバーサル・ドットファイル配信の確認。
# 使い方: bash review/poc/serve_mjs_probe.sh   （リポジトリのルートで実行。5051番ポートを使う）
set -u
cd "$(dirname "$0")/../.."
node reFront/server.mjs >/dev/null 2>&1 &
PID=$!
sleep 1
probe() { printf '%-52s -> ' "$1"; curl -s -o /dev/null -w '%{http_code}\n' --path-as-is "http://localhost:5051$1"; }
probe /index.html
probe /../reBack/pom.xml
probe /%2e%2e/reBack/pom.xml
probe /..%2freBack/pom.xml
probe /%2e%2e%2f%2e%2e%2fetc/passwd
probe /....//....//etc/passwd
probe //etc/passwd
probe /%00
probe /.gitignore
probe /server.mjs
probe /test/smoke.test.mjs
probe /README.md
probe /theme/
echo "--- Host ヘッダーを偽装しても localhost:5051 にしか bind しない（外部アドレスからは届かない）ことの確認"
ss -ltn 2>/dev/null | grep ':5051' || true
curl -s -o /dev/null -w 'Host: evil.example -> %{http_code}\n' -H 'Host: evil.example' http://localhost:5051/index.html
kill $PID 2>/dev/null
