// README（ルート、reBack、reFront）の検査。リポジトリ直下で実行する。
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const files = ['README.md', 'reBack/README.md', 'reFront/README.md'];
const secretPatterns = [
  [/AIza[0-9A-Za-z_-]{35}/, 'Gemini APIキーらしい文字列'],
  [/\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}/, 'BCryptハッシュらしい文字列'],
];

const errors = [];
for (const file of files) {
  if (!existsSync(file)) {
    errors.push(`${file}: ファイルがありません`);
    continue;
  }
  const text = readFileSync(file, 'utf8');

  // 相対リンクの先が実在すること
  for (const m of text.matchAll(/\]\(([^)\s]+)\)/g)) {
    const link = m[1];
    if (/^([a-z][a-z0-9+.-]*:|#)/i.test(link)) continue;
    const target = decodeURIComponent(link.split('#')[0].split('?')[0]);
    if (target && !existsSync(resolve(dirname(file), target))) {
      errors.push(`${file}: リンク切れ ${link}`);
    }
  }

  for (const [pattern, label] of secretPatterns) {
    if (pattern.test(text)) errors.push(`${file}: ${label}を含んでいます`);
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`README ${files.length}ファイル: OK`);
