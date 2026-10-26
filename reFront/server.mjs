// ローカル確認用の静的ファイルサーバー。reFront/ を http://localhost:5051 で配信する。
// config.js の「メニューへ戻る」の移動先と合わせるため、ポートは5051に固定している。
import { createServer } from "node:http";
import { readFile, realpath } from "node:fs/promises";
import { extname, join, normalize, sep } from "node:path";
import { fileURLToPath } from "node:url";

const PORT = 5051;
const HOST = "localhost";
const ROOT = fileURLToPath(new URL(".", import.meta.url));
// シンボリックリンクをたどった先も reFront/ の中かを確かめるため、実体のパスも持つ（末尾の区切りはOSに合わせる。Windowsは「\」）
const REAL_ROOT = (await realpath(ROOT)).replace(/[\\/]?$/, sep);

const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".ico": "image/x-icon",
  ".mp4": "video/mp4"
};

// 許可する Host（DNSリバインディング対策。localhost 以外の名前で来た要求は拒否する）
const ALLOWED_HOSTS = new Set([`localhost:${PORT}`, `127.0.0.1:${PORT}`, `[::1]:${PORT}`]);

const MENU_PATH = "/index.html";

// ブラウザーのページ移動（アドレスバー入力・リンク）による要求か。Sec-Fetch-Mode のない古いブラウザーは Accept で判断する
function isNavigation(request) {
  const mode = request.headers["sec-fetch-mode"];
  if (mode) return mode === "navigate";
  return (request.headers.accept ?? "").includes("text/html");
}

// 同じサイトのページからのリンクか（Referer が同じ Host）
function cameFromSite(request) {
  try {
    return new URL(request.headers.referer ?? "").host === request.headers.host;
  } catch {
    return false;
  }
}

function redirectToMenu(response) {
  response.writeHead(302, { Location: MENU_PATH, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
  response.end();
}

function reject(response, status, message) {
  response.writeHead(status, { "Content-Type": "text/plain; charset=utf-8", "X-Content-Type-Options": "nosniff" });
  response.end(message);
}

const server = createServer(async (request, response) => {
  try {
    if (request.method !== "GET" && request.method !== "HEAD") return reject(response, 405, "Method Not Allowed");
    if (!ALLOWED_HOSTS.has(request.headers.host ?? "")) return reject(response, 403, "Forbidden");

    // メニュー以外への直接アクセス（同じサイトのページからのリンク以外のページ移動）はメニューへ移動させる
    const navigation = isNavigation(request);
    const requestPath = new URL(request.url, `http://${HOST}`).pathname;
    // 動画ページ（/video/show/）は直接開いてもよいので転送しない
    const isVideoPage = requestPath.startsWith("/video/show/") && requestPath.endsWith(".html");
    if (navigation && requestPath !== MENU_PATH && !isVideoPage && !cameFromSite(request)) return redirectToMenu(response);

    let pathname;
    try {
      pathname = decodeURIComponent(new URL(request.url, `http://${HOST}`).pathname);
    } catch {
      return reject(response, 400, "Bad Request");   // 不正な %エンコード
    }
    if (pathname.includes("\0")) return reject(response, 400, "Bad Request");
    // ドットで始まるファイル・ディレクトリ（.gitignore など）は配信しない
    if (pathname.split("/").some((segment) => segment.startsWith("."))) return reject(response, 404, "Not Found");

    const relativePath = pathname.endsWith("/") ? `${pathname}index.html` : pathname;
    const filePath = normalize(join(ROOT, relativePath));
    if (!filePath.startsWith(ROOT)) throw new Error("outside of reFront");
    const realFilePath = await realpath(filePath);
    if (!realFilePath.startsWith(REAL_ROOT)) throw new Error("outside of reFront");

    const body = await readFile(realFilePath);
    response.writeHead(200, {
      "Content-Type": CONTENT_TYPES[extname(filePath).toLowerCase()] ?? "application/octet-stream",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff"
    });
    response.end(request.method === "HEAD" ? undefined : body);
  } catch {
    // 存在しないページへの移動もメニューへ
    if (isNavigation(request)) return redirectToMenu(response);
    reject(response, 404, "Not Found");
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Frontend: http://${HOST}:${PORT}/index.html`);
});
