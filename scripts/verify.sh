#!/usr/bin/env bash
# 動作確認。4つの検査を直列に実行し、失敗しても最後まで進めてログに残す。
#
# 使い方（リポジトリ直下で実行）:
#   scripts/verify.sh <ログファイル> <nodeテスト出力ファイル>
#
# 終了コード: 0=すべて合格 / 1=どれかが失敗
set -uo pipefail

log=${1:?ログファイルを指定してください}
export NODE_TEST_OUTPUT=${2:?nodeテスト出力ファイルを指定してください}

{
  ok=true
  (cd reBack && mvn -B test) || ok=false
  node --test reFront/test/smoke.test.mjs > "$NODE_TEST_OUTPUT" 2>&1 || ok=false
  tail -n 12 "$NODE_TEST_OUTPUT"
  node scripts/check_readme.mjs || ok=false
  node scripts/check_doc.mjs || ok=false
  [ "$ok" = true ]
} > "$log" 2>&1
rc=$?
tail -n 40 "$log"
exit "$rc"
