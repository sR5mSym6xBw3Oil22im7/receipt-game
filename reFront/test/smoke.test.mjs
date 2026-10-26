import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const html = await readFile(new URL("../../reBack/src/main/resources/static/admin/upload.html", import.meta.url), "utf8");
const index = await readFile(new URL("../index.html", import.meta.url), "utf8");
const select = await readFile(new URL("../../reBack/src/main/resources/static/admin/select.html", import.meta.url), "utf8");
const js = await readFile(new URL("../../reBack/src/main/resources/static/admin/app.js", import.meta.url), "utf8");
const selectJs = await readFile(new URL("../../reBack/src/main/resources/static/admin/select.js", import.meta.url), "utf8");
const config = await readFile(new URL("../config.js", import.meta.url), "utf8");
const indexJs = await readFile(new URL("../index.js", import.meta.url), "utf8");
const adminApi = await readFile(new URL("../../reBack/src/main/resources/static/admin/admin-api.js", import.meta.url), "utf8");
const loginJs = await readFile(new URL("../../reBack/src/main/resources/static/admin/login.js", import.meta.url), "utf8");

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
  assert.match(html, /<script src="\.\/app\.js\?v=20261013-db-label"><\/script>/);
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
  assert.match(html, /id="save-button"[^>]*>データベースへ保存<\/button>/);
  assert.match(js, /\/api\/receipts\/analyze/);
  assert.match(js, /\/api\/receipts\/save/);
  assert.match(js, /saveButton\.addEventListener\("click"/);
  assert.match(js, /枚の解析が完了しました/);
  assert.match(js, /データベースへ保存しました/);
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
  assert.match(config, /receipt-game\.onrender\.com/);
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
  assert.match(js, /GEMINI_API_ANALYZE（未設定ならGEMINI_API_DEFAULT）を設定してください/);
});

test("frontend does not persist the Gemini API key in browser storage", () => {
  assert.doesNotMatch(js, /localStorage/);
  assert.doesNotMatch(js, /sessionStorage/);
});

test("index links to game, demo and admin login in that order", () => {
  assert.doesNotMatch(index, /admin-upload-link|id="select-link"|レシートを解析|保存済みレシートを確認する/);
  const order = ["id=\"game-link\"", "id=\"demo-link\"", "id=\"admin-login-link\""].map((key) => index.indexOf(key));
  assert.ok(order[0] > 0 && order[0] < order[1] && order[1] < order[2]);
  assert.match(index, /id="admin-login-link"[^>]*href="https:\/\/receipt-game\.onrender\.com\/admin\/login\.html"/);
});

test("public index routes admin login to Backend", () => {
  assert.doesNotMatch(indexJs, /fetch\s*\(/);
  assert.doesNotMatch(indexJs, /api\/receipts/);
  assert.match(indexJs, /ADMIN_BASE_URL/);
  assert.match(indexJs, /admin-login-link/);
  assert.match(indexJs, /login\.html/);
});

test("admin pages use Backend session authentication and CSRF instead of Referrer guards", () => {
  assert.match(select, /<script src="\.\/admin-api\.js\?v=20261022-csrf-origin"><\/script>/);
  assert.match(html, /<script src="\.\/admin-api\.js\?v=20261022-csrf-origin"><\/script>/);
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

test("reFront config.js matches Backend config.js apart from menu URLs", async () => {
  for (const name of ["config.js"]) {
    const front = await readFile(new URL(`../${name}`, import.meta.url), "utf8");
    const back = await readFile(new URL(`../../reBack/src/main/resources/static/admin/${name}`, import.meta.url), "utf8");
    // reFront/config.js だけは、メニュー用の DEMO_URL / GAME_URL の2行が多い（意図した差）
    const normalize = (text) => (name === "config.js" ? text.split("\n").filter((line) => !/^\s*(DEMO_URL|GAME_URL):/.test(line)).join("\n") : text);
    assert.equal(normalize(front), normalize(back), `${name} differs between reFront and reBack/static/admin`);
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

test("upload page limits file size, ZIP entries and decompressed size before sending", () => {
  assert.match(js, /MAX_IMAGE_BYTES = 5 \* 1024 \* 1024/);
  assert.match(js, /MAX_ZIP_ENTRIES = 50/);
  assert.match(js, /MAX_ZIP_TOTAL_BYTES/);
  assert.match(js, /uncompressedSize > MAX_IMAGE_BYTES/);
  assert.match(js, /inflateRaw\(compressed, MAX_IMAGE_BYTES\)/);
  assert.match(js, /file\.size === 0/);
});

test("login form limits input length in the browser and the server limit is mirrored", async () => {
  const loginHtml = await readFile(new URL("../../reBack/src/main/resources/static/admin/login.html", import.meta.url), "utf8");
  assert.match(loginHtml, /name="username"[^>]*maxlength="64"/);
  assert.match(loginHtml, /name="password"[^>]*maxlength="128"/);
  assert.match(loginJs, /username\.length > 64 \|\| password\.length > 128/);
  assert.match(loginJs, /response\.status === 400/);
});

test("server.mjs rejects foreign Host, dotfiles, bad encodings and non-GET methods", async () => {
  const { spawn } = await import("node:child_process");
  const { fileURLToPath } = await import("node:url");
  // URL の pathname は Windows で「/E:/...」になり起動できないので、fileURLToPath でパスにする
  const child = spawn(process.execPath, [fileURLToPath(new URL("../server.mjs", import.meta.url))], { stdio: "ignore" });
  try {
    await new Promise((resolve) => setTimeout(resolve, 800));
    const get = (path, init = {}) => fetch(`http://localhost:5051${path}`, init).then((r) => r.status);
    assert.equal(await get("/index.html"), 200);
    assert.equal(await get("/.gitignore"), 404);
    assert.equal(await get("/%E0%A4%A"), 400);
    assert.equal(await get("/%00"), 400);
    assert.equal(await get("/index.html", { method: "POST" }), 405);
    const res = await new Promise((resolve, reject) => {
      import("node:http").then(({ request }) => {
        // 応答を読み捨て、エラーも受け取る（サーバー停止時の ECONNRESET がテスト後に未処理で残らないように）
        const r = request({ host: "localhost", port: 5051, path: "/index.html", headers: { Host: "evil.example" } }, (m) => {
          m.resume();
          resolve(m.statusCode);
        });
        r.on("error", reject);
        r.end();
      });
    });
    assert.equal(res, 403);

    // メニュー以外へのページ移動は、同じサイトからのリンクでなければメニューへ戻す
    // Node の fetch は Sec-Fetch-Mode を自分で上書きして navigate を送れないため、node:http で送る
    const { request } = await import("node:http");
    const nav = (path, headers = {}) => new Promise((resolve, reject) => {
      const r = request({ host: "localhost", port: 5051, path, headers: { "Sec-Fetch-Mode": "navigate", ...headers } }, (m) => {
        m.resume();
        resolve({ status: m.statusCode, headers: { get: (name) => m.headers[name.toLowerCase()] ?? null } });
      });
      r.on("error", reject);
      r.end();
    });
    assert.equal((await nav("/index.html")).status, 200);
    for (const path of ["/", "/video/pv/pv01.mp4", "/styles.css", "/no-such-page.html", "/.gitignore"]) {
      const r = await nav(path);
      assert.equal(r.status, 302, path);
      assert.equal(r.headers.get("location"), "/index.html", path);
    }
    assert.equal((await nav("/video/show/pv1.html", { Referer: "http://localhost:5051/index.html" })).status, 200);
    assert.equal((await nav("/video/show/pv1.html")).status, 200);   // 動画ページは直接アクセスでも転送しない
    assert.equal((await nav("/video/show/pv1.html", { Referer: "https://evil.example/" })).status, 200);
    assert.equal((await nav("/no-such-page.html", { Referer: "http://localhost:5051/index.html" })).status, 302);
    assert.equal(await get("/styles.css"), 200);   // ページが読み込むCSS・スクリプト・動画はそのまま配信する
  } finally {
    child.kill();
  }
});

// ---- モンスターレシート対戦の演出（本番 /game/ とデモ /demo/ で共通の fx.css・fx.js） ----
const staticDir = "../../reBack/src/main/resources/static/";
const readStatic = (p) => readFile(new URL(staticDir + p, import.meta.url), "utf8");
const gameHtml = await readStatic("game/index.html");
const demoHtml = await readStatic("demo/index.html");
const fxCss = await readStatic("game/fx.css");
const fxJs = await readStatic("game/fx.js");
const gameJs = await readStatic("game/game.js");
const demoJs = await readStatic("demo/demo.js");

test("game / demo: index.html が fx.css と fx.js を ?v= 付きで読み込み、演出レイヤーと明細を持つ", () => {
  for (const [name, html, fxPrefix] of [["game", gameHtml, "./"], ["demo", demoHtml, "../game/"]]) {
    assert.match(html, new RegExp(`<link rel="stylesheet" href="${fxPrefix.replace(/[./]/g, "\\$&")}fx\\.css\\?v=[\\w-]+">`), `${name}: fx.css`);
    assert.match(html, new RegExp(`<script src="${fxPrefix.replace(/[./]/g, "\\$&")}fx\\.js\\?v=[\\w-]+"></script>`), `${name}: fx.js`);
    assert.match(html, /<script src="\.\/(game|demo)\.js\?v=[\w-]+"><\/script>/, `${name}: 画面のJS`);
    assert.match(html, /<div id="fx-layer" aria-hidden="true"><\/div>/, `${name}: fx-layer`);
    assert.match(html, /id="result-slip"/, `${name}: result-slip`);
    assert.match(html, /id="battle-arena"/, `${name}: battle-arena`);
    // fx.js は画面のJS（MonsterFx を使う側）より前に読み込む
    assert.ok(html.indexOf("fx.js") < html.search(/(game|demo)\.js\?v=/), `${name}: 読み込み順`);
  }
});

test("fx.css: fx- クラスの演出があり、prefers-reduced-motion で止まる", () => {
  assert.match(fxCss, /\.fx-cutin\b/);
  assert.match(fxCss, /\.fx-slip\b/);
  const reduce = fxCss.slice(fxCss.lastIndexOf("@media (prefers-reduced-motion: reduce)"));
  for (const cls of [".fx-pillar", ".fx-coin", ".fx-ko", ".fx-ch", ".fx-slip-row", ".fx-drum-strip", ".mcard.is-revealed"]) {
    assert.ok(reduce.includes(cls), `${cls} が動き止めの対象にありません`);
  }
  assert.match(fxJs, /prefers-reduced-motion: reduce/);
  assert.doesNotMatch(fxCss + fxJs, /<canvas|requestAnimationFrame/);
});

test("game.js / demo.js は MonsterFx を呼び、後片付けとカットインの一時停止を行う", () => {
  for (const [name, js] of [["game.js", gameJs], ["demo.js", demoJs]]) {
    assert.match(js, /window\.MonsterFx/, name);
    for (const call of ["FX.roomCode(", "FX.copied(", "FX.waitRing(", "FX.summon(", "FX.battleIntro(", "FX.hit(", "FX.cutIn(", "FX.ko(", "FX.result(", "FX.clear("]) {
      assert.ok(js.includes(call), `${name} に ${call} がありません`);
    }
    assert.match(js, /FX\.seat(Joined|State)\(/, `${name}: 参戦ハンコ`);
    assert.match(js, /r\.pending/, `${name}: カットイン中の一時停止`);
  }
});

test("fx.js: カード名・必殺技名などは textContent で入れ、innerHTML には固定のSVGだけ", () => {
  const body = (name) => {
    const start = fxJs.indexOf(`function ${name}(`);
    assert.ok(start >= 0, name);
    return fxJs.slice(start, fxJs.indexOf("\n  }\n", start));
  };
  for (const name of ["cutIn", "slip", "damage", "lucky", "roomCode"]) {
    assert.doesNotMatch(body(name), /innerHTML|insertAdjacentHTML/, name);
    assert.match(body(name), /textContent/, name);
  }
  assert.match(body("cutIn"), /skill\.textContent = e\.skillName/);
  assert.match(body("cutIn"), /who\.textContent = card\.name/);
  // innerHTML / insertAdjacentHTML に渡すのは定数のSVGだけ
  for (const m of fxJs.matchAll(/(?:innerHTML\s*=|insertAdjacentHTML\([^,]+,)\s*([^;)]+)/g)) {
    assert.match(m[1].trim(), /^[A-Z_]+_SVG$/, m[0]);
  }
  assert.match(fxJs, /function clear\(/);
});

test("direct-access-guard: メニューが先頭で読み込み、動画ページは読み込まず、404.html がメニューへ移動する", async () => {
  const menuHtml = await readFile(new URL("../index.html", import.meta.url), "utf8");
  assert.match(menuHtml, /<meta name="viewport"[^>]*>\n  <script src="\.\/direct-access-guard\.js\?v=[^"]+"><\/script>/);
  // 動画ページは直接開いても転送しない
  for (const p of ["guide6min", "guide3min", "pv1", "pv2"]) {
    const pageHtml = await readFile(new URL(`../video/show/${p}.html`, import.meta.url), "utf8");
    assert.doesNotMatch(pageHtml, /direct-access-guard/, p);
  }
  const notFound = await readFile(new URL("../../404.html", import.meta.url), "utf8");
  assert.match(notFound, /reFront\/index\.html/);
});

// ---- OWASP（DOM based XSS・CSRF）の対策が外れていないこと ----
test("game / demo: HTML文字列へ差し込む値（属性値・数値を含む）は esc() を通す", () => {
  for (const [name, js] of [["game", gameJs], ["demo", demoJs]]) {
    assert.doesNotMatch(js, /\$\{card\.(id|hp|atk|def|spd|luck|rarity|element)\}/, `${name}: カードの値`);
    assert.doesNotMatch(js, /class="\$\{cls\}"/, `${name}: class属性`);
    assert.doesNotMatch(js, /\$\{it\.quantity\}/, `${name}: 数量`);
    assert.match(js, /class="\$\{esc\(cls\)\}"/, `${name}: class属性をエスケープ`);
  }
});

test("login.js: ログイン後の移動先は同じオリジンの /admin/ 配下だけ", async () => {
  const { runInNewContext } = await import("node:vm");
  const source = loginJs.match(/function safeAdminDestination[\s\S]*?\n}\n/)[0];
  const window = { location: { origin: "https://backend.example" } };
  const safe = runInNewContext(`${source}; safeAdminDestination`, { window, URL });
  assert.equal(safe("/admin/menu.html"), "/admin/menu.html");
  assert.equal(safe("/admin/select.html?x=1"), "/admin/select.html?x=1");
  assert.equal(safe("https://evil.example/admin/menu.html"), "/admin/menu.html");
  assert.equal(safe("//evil.example/admin/"), "/admin/menu.html");
  assert.equal(safe("javascript:alert(1)"), "/admin/menu.html");
  assert.equal(safe("/game/index.html"), "/admin/menu.html");
  assert.equal(safe(undefined), "/admin/menu.html");
});

test("admin-api.js: CSRFトークンは管理APIのオリジン以外へ送らない", async () => {
  const { runInNewContext } = await import("node:vm");
  const requests = [];
  const context = {
    URL, Headers,
    window: { APP_CONFIG: { API_BASE_URL: "https://backend.example" }, location: { origin: "https://backend.example" } },
    document: { getElementById: () => null },
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: true, status: 200, json: async () => ({ token: "csrf-token" }) };
    }
  };
  runInNewContext(adminApi, context);
  await context.adminFetch("/api/receipts/save", { method: "POST" });
  assert.equal(requests.at(-1).url, "https://backend.example/api/receipts/save");
  assert.equal(requests.at(-1).options.headers.get("X-XSRF-TOKEN"), "csrf-token");
  requests.length = 0;
  await assert.rejects(context.adminFetch("https://evil.example/collect", { method: "POST" }));
  assert.equal(requests.length, 0, "別オリジンにはトークンの取得も送信もしない");
});

// ---- 講評への対応（待機画面のレイアウト・メニュー冒頭の画面イメージ） ----
test("game.js / demo.js: 待機中の秒数は固定幅の要素だけを書き換え、文章を毎秒作り直さない", () => {
  assert.match(gameJs, /function renderWaitCountdown\(/);
  assert.match(gameJs, /\.wait-sec/);
  assert.doesNotMatch(gameJs, /あと \$\{room\.waitSecondsLeft\} 秒/);
  assert.match(demoJs, /function renderWaitCountdown\(/);
  assert.doesNotMatch(demoJs, /あと \$\{left\} 秒/);
  assert.match(fxCss, /\.wait-sec \{[^}]*min-width:[^}]*tabular-nums/);
});

test("menu: 対戦画面のイメージと遊び方をメニューより前に置き、showcase.css を ?v= 付きで読み込む", () => {
  assert.match(index, /<link rel="stylesheet" href="\.\/showcase\.css\?v=[\w-]+">/);
  const showcase = index.indexOf('class="showcase"');
  assert.ok(showcase > 0 && showcase < index.indexOf('id="game-link"'));
  assert.ok(index.indexOf('class="sc-steps"') < index.indexOf('id="game-link"'));
  // イラストは固定のインラインSVGだけ（外部の画像やスクリプトを読み込まない）
  assert.doesNotMatch(index.slice(showcase, index.indexOf('class="sc-steps"')), /<img|<script|href=/);
});

test("game / demo: 対戦中は見出し・説明・進行状況を隠し、battleSeed とコンピュータの理由は結果画面に出す", async () => {
  for (const [js, html, css] of [[gameJs, gameHtml, await readStatic("game/game.css")], [demoJs, demoHtml, await readStatic("demo/demo.css")]]) {
    assert.match(js, /document\.body\.classList\.toggle\("is-battling", view === "battle"\)/);
    assert.match(css, /\.is-battling \.page-actions, \.is-battling \.hero-mini, \.is-battling \.footer-note, \.is-battling #view-battle \.steps \{ display: none; \}/);
    const battle = html.slice(html.indexOf('id="view-battle"'), html.indexOf('id="view-result"'));
    assert.doesNotMatch(battle, /id="battle-seed"|id="cpu-note"/);
    assert.match(html.slice(html.indexOf('id="view-result"')), /id="cpu-note"/);
  }
});

test("game / demo: スマートフォンの幅ではレシートを2列・1列に減らす（3列のままだと文字が縦に1文字ずつ折り返す）", async () => {
  for (const css of [await readStatic("game/game.css"), await readStatic("demo/demo.css")]) {
    assert.match(css, /@media \(max-width: 900px\) \{ \.receipt-field \{ grid-template-columns: repeat\(2, minmax\(0, 1fr\)\); \} \}/);
    assert.match(css, /@media \(max-width: 560px\) \{ \.receipt-field \{ grid-template-columns: minmax\(0, 1fr\); \} \}/);
  }
});
