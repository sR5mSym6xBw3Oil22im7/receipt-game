import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const html = await readFile(new URL("../../reBack/src/main/resources/static/admin/upload.html", import.meta.url), "utf8");
const index = await readFile(new URL("../index.html", import.meta.url), "utf8");
const select = await readFile(new URL("../../reBack/src/main/resources/static/admin/select.html", import.meta.url), "utf8");
const js = await readFile(new URL("../app.js", import.meta.url), "utf8");
const selectJs = await readFile(new URL("../select.js", import.meta.url), "utf8");
const config = await readFile(new URL("../config.js", import.meta.url), "utf8");
const indexJs = await readFile(new URL("../index.js", import.meta.url), "utf8");
const adminApi = await readFile(new URL("../admin-api.js", import.meta.url), "utf8");
const loginJs = await readFile(new URL("../login.js", import.meta.url), "utf8");

test("frontend accepts JPEG, PNG, and ZIP", () => {
  assert.match(html, /accept="image\/jpeg,image\/png,\.zip,application\/zip"/);
  assert.equal((html.match(/name="file"/g) ?? []).length, 1);
  assert.equal((html.match(/ファイルを選択/g) ?? []).length, 1);
  assert.match(html, /selected-file-name/);
  assert.match(html + js, /error-message/);
});

test("ZIP analysis ignores directories but rejects other files and empty archives", () => {
  assert.match(js, /function isZipDirectory\(name, versionMadeBy, externalAttributes\)/);
  assert.match(js, /if \(isZipDirectory\(name, versionMadeBy, externalAttributes\)\) continue;/);
  assert.match(js, /if \(!\/\\\.\(jpe\?g\|png\)\$\/i\.test\(name\)\)/);
  assert.match(js, /ZIPファイルにレシート画像がありません/);
});

test("analysis reports per-image progress and does not wait forever", () => {
  assert.match(js, /ANALYZE_REQUEST_TIMEOUT_MS = 210000/);
  assert.match(js, /ANALYSIS_WAIT_MESSAGE_INTERVAL_MS = 15000/);
  assert.match(js, /setInterval\(\(\) =>/);
  assert.match(js, /clearInterval\(waitMessageTimer\)/);
  assert.match(js, /ブラウザを閉じずにお待ちください/);
  assert.match(js, /signal: controller\.signal/);
  assert.match(js, /画像\$\{fileNumber\}\/\$\{selectedFiles\.length\}を解析中です/);
  assert.match(js, /BACKEND_TIMEOUT/);
});


test("upload page cache-busts app.js so old upload code is not reused", () => {
  assert.match(html, /<script src="\.\/app\.js\?v=20261007-server-gemini-key"><\/script>/);
});

test("frontend posts multipart data to the receipt endpoint", () => {
  assert.match(js, /FormData/);
  assert.match(js, /\/api\/receipts/);
  assert.match(js, /method:\s*"POST"/);
});

test("frontend keeps each selected receipt result separate", () => {
  assert.match(html, /id="receipt-results"/);
  assert.match(js, /renderReceiptResults/);
  assert.match(js, /fileNumber/);
  assert.match(js, /saveReceipt/);
});

test("analysis and PostgreSQL save are separate operations", () => {
  assert.match(html, /id="submit-button"[^>]*>解析<\/button>/);
  assert.match(html, /id="save-button"[^>]*>PostgreSQLへ保存<\/button>/);
  assert.match(js, /\/api\/receipts\/analyze/);
  assert.match(js, /\/api\/receipts\/save/);
  assert.match(js, /saveButton\.addEventListener\("click"/);
  assert.match(js, /枚の解析が完了しました/);
  assert.match(js, /PostgreSQLへ保存しました/);
  assert.doesNotMatch(js, /未保存 \/ /);
  assert.doesNotMatch(js, /PostgreSQL保存済み/);
});

test("save button stores each analyzed receipt", () => {
  assert.match(js, /continue;/);
  assert.match(js, /saveReceipt\(receipt\.lines, receipt\.sha256, receipt\.structuredData\)/);
  assert.match(js, /body: JSON\.stringify\(\{ lines, sha256, structuredData \}\)/);
  assert.match(js, /if \(busy\) return;/);
  assert.match(js, /receipt\.stored = true/);
  assert.match(js, /SAVE_REQUEST_TIMEOUT_MS/);
  assert.match(js, /AbortController/);
  assert.match(js, /バックエンドサーバーから応答がありません/);
  assert.doesNotMatch(js, /PostgreSQLへ追加しました/);
  assert.match(js, /resetUploadPage\(\)/);
});

test("frontend has a configurable Render backend URL", () => {
  assert.match(config, /API_BASE_URL/);
  assert.match(config, /onrender\.com/);
  assert.doesNotMatch(config, /YOUR-RENDER-SERVICE/);
  assert.match(config, /receipt-analysis-b8po\.onrender\.com/);
});

test("upload page has no Gemini API key input", () => {
  assert.doesNotMatch(html, /gemini-api-key/);
  assert.doesNotMatch(html, /type="password"/);
});

test("frontend does not send a Gemini API key", () => {
  assert.doesNotMatch(js, /geminiApiKey/);
  assert.doesNotMatch(js, /apiKeyInput/);
});

test("frontend explains server-side Gemini API key errors", () => {
  assert.match(js, /GEMINI_QUOTA_EXCEEDED/);
  assert.match(js, /GEMINI_API_KEY_MISSING/);
  assert.match(js, /GEMINI_API_KEYを設定してください/);
});

test("frontend does not persist the Gemini API key in browser storage", () => {
  assert.doesNotMatch(js, /localStorage/);
  assert.doesNotMatch(js, /sessionStorage/);
});

test("index links to game, demo and admin login in that order", () => {
  assert.doesNotMatch(index, /admin-upload-link|id="select-link"|レシートを解析|保存済みレシートを確認する/);
  const order = ["id=\"game-link\"", "id=\"demo-link\"", "id=\"admin-login-link\""].map((key) => index.indexOf(key));
  assert.ok(order[0] > 0 && order[0] < order[1] && order[1] < order[2]);
  assert.match(index, /id="admin-login-link"[^>]*href="https:\/\/receipt-analysis-b8po\.onrender\.com\/admin\/login\.html"/);
});

test("public index routes admin login to Backend", () => {
  assert.doesNotMatch(indexJs, /fetch\s*\(/);
  assert.doesNotMatch(indexJs, /api\/receipts/);
  assert.match(indexJs, /ADMIN_BASE_URL/);
  assert.match(indexJs, /admin-login-link/);
  assert.match(indexJs, /login\.html/);
});

test("admin pages use Backend session authentication and CSRF instead of Referrer guards", () => {
  assert.match(select, /<script src="\.\/admin-api\.js\?v=20261011-keep-href2"><\/script>/);
  assert.match(html, /<script src="\.\/admin-api\.js\?v=20261011-keep-href2"><\/script>/);
  assert.doesNotMatch(select + html, /access-guard\.js|document\.referrer|history\.replaceState/);
  assert.match(adminApi, /X-XSRF-TOKEN/);
  assert.match(adminApi, /credentials: "same-origin"/);
  assert.match(loginJs, /\/api\/auth\/login/);
  assert.match(loginJs, /\/admin\/menu\.html/);
  assert.doesNotMatch(loginJs, /\.value\s*===?\s*["']/);
});

test("select page loads receipt list and detail bubble", () => {
  assert.match(select, /id="receipt-list"/);
  assert.match(select, /id="detail-bubble"/);
  assert.match(select, /id="close-detail"/);
  assert.match(select, /id="delete-selected"/);
  assert.match(select, /id="delete-selected"[^>]*hidden/);
  assert.match(selectJs, /checkbox/);
  assert.match(selectJs, /\/api\/receipts/);
  assert.match(selectJs, /detailBubble/);
  assert.match(selectJs, /method:\s*"DELETE"/);
  assert.match(selectJs, /selectedTableNames/);
  assert.match(selectJs, /receiptCount/);
  assert.match(select, /チェックしたレシートを削除/);
});

test("reFront copies of admin scripts match the files served by Backend", async () => {
  for (const name of ["app.js", "select.js", "login.js", "admin-api.js", "config.js"]) {
    const front = await readFile(new URL(`../${name}`, import.meta.url), "utf8");
    const back = await readFile(new URL(`../../reBack/src/main/resources/static/admin/${name}`, import.meta.url), "utf8");
    assert.equal(front, back, `${name} differs between reFront and reBack/static/admin`);
  }
});

test("admin pages offer logout through the CSRF-protected API", () => {
  assert.match(html, /id="logout-button"/);
  assert.match(select, /id="logout-button"/);
  assert.match(adminApi, /adminFetch\("\/api\/auth\/logout", \{ method: "POST" \}\)/);
  assert.match(adminApi, /\/admin\/login\.html/);
});

test("analysis and save send the admin session cookie and CSRF token", () => {
  assert.match(js, /adminFetch\(`\$\{API_BASE_URL\}\/api\/receipts\/analyze`/);
  assert.match(js, /return await adminFetch\(url, \{ \.\.\.options, signal: controller\.signal \}\)/);
  assert.doesNotMatch(js, /credentials: "omit"/);
  assert.doesNotMatch(js, /(?<!admin)fetch\(/);
  assert.match(js, /response\.status === 401/);
  assert.match(js, /管理画面に再度ログインしてください/);
  assert.ok(html.indexOf("admin-api.js") < html.indexOf("app.js"), "admin-api.js must load before app.js");
});

// ---- 日替わりテーマ（毎日3時・日本時間に10種類が順番に切り替わる） ----
import { readdir } from "node:fs/promises";
import vm from "node:vm";

const themeDirBack = new URL("../../reBack/src/main/resources/static/theme/", import.meta.url);
const themeDirFront = new URL("../theme/", import.meta.url);

function loadTheme() {
  const context = { window: {}, document: { currentScript: null, documentElement: { setAttribute() {} }, head: { querySelector: () => null, appendChild() {} }, createElement: () => ({ setAttribute() {}, getAttribute: () => null }), querySelectorAll: () => [], addEventListener() {} }, location: { search: "" }, setTimeout() {}, URLSearchParams, Date };
  context.window = context;
  vm.runInNewContext(themeSource, context);
  return context.ReceiptTheme;
}
const themeSource = await readFile(new URL("theme.js", themeDirBack), "utf8");

test("theme: 毎日3時（日本時間）に切り替わり、10種類が順番に回る", () => {
  const { themeIndex, nextSwitchAt, THEMES } = loadTheme();
  assert.equal(THEMES.length, 10);
  const jst = (s) => Date.parse(`${s}+09:00`);
  assert.equal(themeIndex(jst("2026-10-11T02:59:59")), themeIndex(jst("2026-10-10T03:00:00")));
  assert.equal(themeIndex(jst("2026-10-11T03:00:00")), (themeIndex(jst("2026-10-10T03:00:00")) + 1) % 10);
  assert.equal(nextSwitchAt(jst("2026-10-11T10:00:00")), jst("2026-10-12T03:00:00"));
  assert.equal(nextSwitchAt(jst("2026-10-11T02:00:00")), jst("2026-10-11T03:00:00"));
  const seen = new Set();
  for (let d = 0; d < 10; d++) seen.add(themeIndex(jst("2026-10-01T12:00:00") + d * 86400000));
  assert.equal(seen.size, 10);
});

test("theme: base.css と10テーマのCSSがあり、フロントとバックエンドで同じ内容", async () => {
  const names = ["theme.js", "base.css", ...Array.from({ length: 10 }, (_, i) => `t${String(i + 1).padStart(2, "0")}.css`)];
  assert.deepEqual((await readdir(themeDirBack)).sort(), [...names].sort());
  for (const name of names) {
    assert.equal(await readFile(new URL(name, themeDirFront), "utf8"), await readFile(new URL(name, themeDirBack), "utf8"), `${name} がずれています`);
  }
});

test("theme: すべてのページが theme.js を読み込む", async () => {
  const pages = ["../index.html", ...["admin/login", "admin/menu", "admin/upload", "admin/select", "game/index", "demo/index"].map((p) => `../../reBack/src/main/resources/static/${p}.html`)];
  for (const page of pages) assert.match(await readFile(new URL(page, import.meta.url), "utf8"), /<script src="(\.|\.\.)\/theme\/theme\.js"><\/script>/, page);
});
