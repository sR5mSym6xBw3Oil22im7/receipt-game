// doc/ の検査。リポジトリ直下で実行する。
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const dir = 'doc';
const secretPatterns = [
  [/AIza[0-9A-Za-z_-]{35}/, 'Gemini APIキーらしい文字列'],
  [/\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}/, 'BCryptハッシュらしい文字列'],
];
const noindexAfterViewport =
  /<meta name="viewport"[^>]*>\s*<meta name="robots" content="noindex, nofollow">/;

const pages = readdirSync(dir).filter((f) => f.endsWith('.html')).sort();
const errors = [];

for (const page of pages) {
  const text = readFileSync(`${dir}/${page}`, 'utf8');

  // viewport の直後に noindex があること
  if (!noindexAfterViewport.test(text)) {
    errors.push(`${dir}/${page}: viewport の直後に noindex, nofollow がありません`);
  }

  // 相対リンク（href / src）の先が実在すること
  for (const m of text.matchAll(/\b(?:href|src)="([^"]*)"/g)) {
    const link = m[1];
    if (link === '' || /^([a-z][a-z0-9+.-]*:|#)/i.test(link)) continue;
    const target = link.split('#')[0].split('?')[0];
    if (target && !existsSync(resolve(dir, target))) {
      errors.push(`${dir}/${page}: リンク切れ ${link}`);
    }
  }

  for (const [pattern, label] of secretPatterns) {
    if (pattern.test(text)) errors.push(`${dir}/${page}: ${label}を含んでいます`);
  }
}

// 目次が 01〜35 の全文書を網羅していること
const index = readFileSync(`${dir}/index.html`, 'utf8');
for (const page of pages.filter((f) => /^\d\d_/.test(f))) {
  if (!index.includes(`href="${page}"`)) {
    errors.push(`${dir}/index.html: 目次に ${page} がありません`);
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`doc ${pages.length}ファイル: OK`);
