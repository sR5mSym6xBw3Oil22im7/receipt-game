# 方法B：会話履歴ごと別のPCへ移す手順

対象：移行元PCと移行先PCが、どちらも xubuntu / VS Code / Claude Code 拡張機能。
前提：コードはGitHub（`sR5mSym6xBw3Oil22im7/receipt-game`）にプッシュ済み。

> **重要**：会話履歴ファイルには、これまでの会話がすべて入っています。受け渡しに使ったファイルは、取り込み後に必ず削除してください。履歴が再開できない場合は、「方法A（新しい会話＋引き継ぎ文）」に切り替えてください。

## 0. 守ること
- リポジトリのパスは、**両方のPCで同じ `/disk01/github/receipt-game`** にする（履歴の保存先フォルダー名がパスから決まるため。違うと履歴が見つからない）。
- ユーザー名が違っても動くよう、スクリプトは `$HOME` を使っている。
- `.env`（Gemini APIキー）は履歴とは別。移行先で自分で作る。チャットには貼らない。

## 1. 移行元PC：履歴をまとめる
1. 未コミットの変更がないか確認し、あればコミット・プッシュする。
   ```sh
   cd /disk01/github/receipt-game
   git status
   ```
2. 履歴をまとめる（最新の会話が対象）。
   ```sh
   bash handoff/pack_session.sh
   ```
   `~/claude-session-handoff.tar.gz` ができる。別の会話を選ぶ場合は、`ls -lt ~/.claude/projects/-disk01-github-receipt-game/*.jsonl` で一覧を見て、ファイル名を渡す。
   ```sh
   bash handoff/pack_session.sh <ファイル名>.jsonl
   ```
3. 作成されたファイルを別のPCへ運ぶ（どちらか）。
   - USBメモリー：`~/claude-session-handoff.tar.gz` をコピーする。
   - ネットワーク：`scp ~/claude-session-handoff.tar.gz <ユーザー>@<移行先のIP>:~/`

## 2. 移行先PC：コードと履歴を用意する
1. リポジトリを取得する（取得済みなら `git pull`）。
   ```sh
   git clone https://github.com/sR5mSym6xBw3Oil22im7/receipt-game.git /disk01/github/receipt-game
   cd /disk01/github/receipt-game && git pull
   ```
   `/disk01` がない場合は、`sudo mkdir -p /disk01/github && sudo chown $USER /disk01/github` で作る。
2. `.env` を作る（必要なときだけ。デモと要件定義書の確認には不要）。
   ```sh
   cp reBack/.env.example reBack/.env   # 値を自分で設定する
   ```
3. 履歴を取り込む（`handoff/` は、このリポジトリに入っていれば `git pull` で来る。入っていなければ、2つの `.sh` も一緒に運ぶ）。
   ```sh
   bash handoff/unpack_session.sh ~/claude-session-handoff.tar.gz
   ```

## 3. 移行先PC：会話を再開する
1. VS Code で `/disk01/github/receipt-game` を開く（**フォルダーを開く**。パスが同じであること）。
2. Claude Code 拡張機能の会話履歴（過去の会話一覧）から、取り込んだ会話を選んで再開する。
   ターミナルなら、リポジトリのフォルダーで次のコマンドを使う。
   ```sh
   claude --resume
   ```
3. 再開できたら、最初に次を伝えて、現状を確かめる。
   ```
   git pull したあとの状態で、doc/34_monster_receipt_battle_requirements.html（版1.6）と demo/monster-battle/ を読んで、これまでの決定事項と未回答の確認事項を要約してください。
   ```

## 4. 後片付け
```sh
rm -f ~/claude-session-handoff.tar.gz     # 移行先と移行元の両方で削除する
```
USBメモリーに入れた場合は、そこからも削除する。

## うまくいかないとき
| 症状 | 原因と対処 |
| --- | --- |
| 履歴の一覧に出てこない | リポジトリのパスが違う。`/disk01/github/receipt-game` で開き直す。取り込み先は `~/.claude/projects/-disk01-github-receipt-game/`。 |
| `claude --resume` で選べない | VS Code を再起動する。それでも駄目なら、方法A（新しい会話＋引き継ぎ文）にする。 |
| 再開できても動作が不安定 | 拡張機能・CLIのバージョン差が原因のことがある。両方のPCを最新版にそろえる。 |
| `.env` が見つからない | `.env` はGit管理外。`reBack/.env.example` からコピーして作る。 |
