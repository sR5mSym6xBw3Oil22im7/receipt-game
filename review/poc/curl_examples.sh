#!/usr/bin/env bash
# レビューで見つけた問題を、ローカルで起動したバックエンドに対して再現する curl 手順。
# ★ 本番(Render)に対しては実行しないこと（DoS・クォータ消費になる）。
# 使い方: BASE=http://localhost:8081 bash review/poc/curl_examples.sh <番号>
#   ※ ローカル起動時は SESSION_COOKIE_SECURE=false にしておく（curl が Secure Cookie を http で送らないため）。
# この手順そのものは未実行。同等の確認は HttpPocTest（Tomcatを起動したテスト）で実行済み。
set -eu
BASE=${BASE:-http://localhost:8081}
JAR=$(mktemp)
csrf() { curl -s -c "$JAR" -b "$JAR" "$BASE/api/auth/csrf" | sed -E 's/.*"token":"([^"]+)".*/\1/'; }

case "${1:-}" in
1) # SEC-001: 巨大ユーザー名（25M文字）を未認証で送る。LoginAttemptLimiter がキーとして保持し続ける
  T=$(csrf)
  python3 -c 'import sys; sys.stdout.write("{\"username\":\"" + "a"*25000000 + "\",\"password\":\"x\"}")' > /tmp/big-login.json
  curl -s -o /dev/null -w "HTTP %{http_code}\n" -b "$JAR" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' \
       --data-binary @/tmp/big-login.json "$BASE/api/auth/login"
  ;;
3) # SEC-003: 管理者アカウントのロックアウト（ユーザー名を知っていれば未認証で可能）
  for i in 1 2 3 4 5; do
    T=$(csrf)
    curl -s -o /dev/null -w "wrong#$i -> HTTP %{http_code}\n" -b "$JAR" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' \
         -d '{"username":"admin","password":"wrong"}' "$BASE/api/auth/login"
  done
  T=$(csrf)
  curl -s -w "\n正しいパスワードでも -> HTTP %{http_code}\n" -b "$JAR" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' \
       -d '{"username":"admin","password":"<正しいパスワード>"}' "$BASE/api/auth/login"
  ;;
4) # VAL-001: 不正なJSON・Content-Type が 500 になる
  T=$(csrf)
  curl -s -w "\nHTTP %{http_code}\n" -b "$JAR" -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' -d '{broken' "$BASE/api/auth/login"
  curl -s -w "\nHTTP %{http_code}\n" -X POST -H 'Content-Type: application/json' -d '{"index":"x"}' -H 'X-Seat-Token: x' "$BASE/api/monster/rooms/ABCDEF/flip"
  ;;
5) # SEC-013 / VAL-001: 認証前に最大5MBを受信し、6MB以上は 500
  head -c 6000000 /dev/zero > /tmp/6mb.png
  curl -s -o /dev/null -w "6MB (token無) -> HTTP %{http_code}\n" -H 'X-Seat-Token: x' -F 'file=@/tmp/6mb.png;type=image/png' "$BASE/api/monster/rooms/ZZZZZZ/image-card"
  head -c 4900000 /dev/zero > /tmp/49mb.png
  curl -s -o /dev/null -w "4.9MB (存在しないルーム) -> HTTP %{http_code} / %{time_total}s\n" -F 'file=@/tmp/49mb.png;type=image/png' "$BASE/api/monster/rooms/ZZZZZZ/image-card"
  ;;
7) # SEC-007: X-Forwarded-For を毎回変えるとルーム作成の回数制限(5回/10分)を回避できる（MONSTER_TRUST_FORWARDED_FOR=true のとき）
  for i in $(seq 1 8); do
    curl -s -o /dev/null -w "create#$i -> HTTP %{http_code}\n" -X POST -H "X-Forwarded-For: 192.0.2.$i" "$BASE/api/monster/rooms"
  done
  ;;
*) echo "usage: BASE=http://localhost:8081 bash $0 {1|3|4|5|7}"; exit 1 ;;
esac
