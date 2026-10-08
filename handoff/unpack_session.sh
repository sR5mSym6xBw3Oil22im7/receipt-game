#!/usr/bin/env bash
# 会話履歴を取り込む（移行先PCで実行）
# 使い方:  bash unpack_session.sh [claude-session-handoff.tar.gz のパス]
set -euo pipefail
ARCHIVE="${1:-$HOME/claude-session-handoff.tar.gz}"
DIR="$HOME/.claude/projects/-disk01-github-receipt-game"

[ -f "$ARCHIVE" ] || { echo "アーカイブがありません: $ARCHIVE" >&2; exit 1; }
[ -d /disk01/github/receipt-game/.git ] || { echo "先に /disk01/github/receipt-game へリポジトリを取得してください（git clone）" >&2; exit 1; }
mkdir -p "$DIR"
tar -xzf "$ARCHIVE" -C "$DIR"
chmod 600 "$DIR"/*.jsonl
echo "取り込みました。会話の一覧:"
ls -lt --time-style=long-iso "$DIR"/*.jsonl | head -n 3
echo "次に、このフォルダーで claude --resume（またはVS Codeの会話履歴）から選んでください。"
