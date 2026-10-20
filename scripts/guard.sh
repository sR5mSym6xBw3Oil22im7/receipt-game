#!/usr/bin/env bash
# デグレード守衛。Claudeの修正が既存の品質を下げていないかを機械的に判定する。
#
# 使い方（リポジトリ直下で実行）:
#   scripts/guard.sh counts                 合格テスト数を key=value で標準出力へ
#   scripts/guard.sh compare <baselineファイル>  合格テスト数が基準より減っていないか
#   scripts/guard.sh diff <基準コミット>      差分の検査（実行前に git add -A しておく）
#
# 終了コード: 0=合格 / 1=不合格（修正を破棄する）/ 2=人の確認が必要（修正は保留する）
set -euo pipefail

MAX_DIFF_LINES=${MAX_DIFF_LINES:-500}   # 差分の総行数の上限
MAX_DOC_DELETED=${MAX_DOC_DELETED:-50}  # README・docの1ファイルあたりの削除行数の上限

# Claudeが変更してはならないパス（検査そのもの、ワークフロー、秘密情報）
FORBIDDEN='^\.github/|^scripts/|^auto-review/|(^|/)\.env|\.(pem|key)$'
# 変更があれば人が確認するパス（認可、依存、デプロイ）
REVIEW='SecurityConfig\.java$|(^|/)pom\.xml$|(^|/)render\.yml$|(^|/)Dockerfile$'
TESTS='^reBack/src/test/|^reFront/test/'
DOCS='^doc/|(^|/)README\.md$'

# 合格テスト数 = 実行数 - 失敗 - エラー - スキップ
cmd_counts() {
  local java node_out=${NODE_TEST_OUTPUT:-}
  java=$(cat reBack/target/surefire-reports/*.txt 2>/dev/null \
    | grep -h 'Tests run:' \
    | awk -F'[:,]' '{p+=$2-$4-$6-$8} END{print p+0}')
  echo "java=$java"
  if [ -n "$node_out" ] && [ -f "$node_out" ]; then
    echo "node=$(awk '/^# pass /{print $3}' "$node_out" | tail -n 1)"
  else
    echo "node=0"
  fi
}

cmd_compare() {
  local baseline=${1:?基準ファイルを指定してください}
  local now ng=0 key b n
  now=$(cmd_counts)
  for key in java node; do
    b=$(grep "^$key=" "$baseline" | cut -d= -f2)
    n=$(echo "$now" | grep "^$key=" | cut -d= -f2)
    if [ "${n:-0}" -lt "${b:-0}" ]; then
      echo "NG: $key の合格テスト数が減りました（基準 $b → 現在 $n）"
      ng=1
    else
      echo "OK: $key の合格テスト数 基準 $b → 現在 $n"
    fi
  done
  return $ng
}

cmd_diff() {
  local base=${1:?基準コミットを指定してください}
  local ng=0 esc=0 total=0 added deleted path

  while IFS=$'\t' read -r added deleted path; do
    [ -n "$path" ] || continue

    if [ "$added" = "-" ]; then
      echo "要確認: バイナリファイルの変更 $path"; esc=1; continue
    fi
    total=$((total + added + deleted))

    if [[ $path =~ $FORBIDDEN ]]; then
      echo "NG: 変更禁止のパスです $path"; ng=1
    fi
    if [[ $path =~ $REVIEW ]]; then
      echo "要確認: 認可・依存・デプロイに関わるファイルの変更 $path"; esc=1
    fi
    # テストは追加のみ許可（削除・書き換えは弱体化とみなす）
    if [[ $path =~ $TESTS ]] && [ "$deleted" -gt 0 ]; then
      echo "NG: 既存テストの行が削除・変更されています $path（削除 ${deleted}行）"; ng=1
    fi
    if [[ $path =~ $DOCS ]] && [ "$deleted" -gt "$MAX_DOC_DELETED" ]; then
      echo "要確認: 文書の削除が多すぎます $path（削除 ${deleted}行）"; esc=1
    fi
  done < <(git diff --cached --no-renames --numstat "$base")

  if [ "$total" -gt "$MAX_DIFF_LINES" ]; then
    echo "要確認: 差分が大きすぎます（${total}行 > ${MAX_DIFF_LINES}行）"; esc=1
  fi

  # テストの無効化（skip・Disabled など）を追加していないか
  if git diff --cached --no-renames -U0 "$base" -- reBack/src/test reFront/test \
      | grep -E '^\+' | grep -v '^+++' \
      | grep -Eq '@Disabled|@Ignore|\.skip\(|\bskip *:|\.todo\('; then
    echo "NG: テストを無効化する記述が追加されています"; ng=1
  fi

  if [ "$ng" -eq 1 ]; then return 1; fi
  if [ "$esc" -eq 1 ]; then return 2; fi
  echo "OK: 差分の検査に合格しました（${total}行）"
}

case "${1:-}" in
  counts)  cmd_counts ;;
  compare) shift; cmd_compare "$@" ;;
  diff)    shift; cmd_diff "$@" ;;
  *) echo "使い方: $0 counts | compare <baselineファイル> | diff <基準コミット>" >&2; exit 64 ;;
esac
