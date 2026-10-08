#!/usr/bin/env bash
# Claude Code の会話履歴（.jsonl）を1つのファイルにまとめる（移行元PCで実行）
# 使い方:  bash pack_session.sh            … 最新の会話を対象にする
#          bash pack_session.sh <ファイル名>.jsonl … 指定した会話を対象にする
set -euo pipefail
DIR="$HOME/.claude/projects/-disk01-github-receipt-game"
OUT="$HOME/claude-session-handoff.tar.gz"

[ -d "$DIR" ] || { echo "履歴フォルダーが見つかりません: $DIR" >&2; exit 1; }
if [ $# -ge 1 ]; then
  FILE="$(basename "$1")"
else
  FILE="$(ls -t "$DIR"/*.jsonl | head -n 1 | xargs -n1 basename)"
fi
[ -f "$DIR/$FILE" ] || { echo "ファイルがありません: $DIR/$FILE" >&2; exit 1; }

echo "対象の会話ファイル:"
ls -l --time-style=long-iso "$DIR/$FILE"
tar -czf "$OUT" -C "$DIR" "$FILE"
chmod 600 "$OUT"
echo "作成しました: $OUT"
echo "※会話の全内容が入っています。別PCへ移したら、移行元・USBメモリーから削除してください。"
