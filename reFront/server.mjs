// ローカル確認用の静的ファイルサーバー。reFront/ を http://localhost:5051 で配信する。
// config.js の「メニューへ戻る」の移動先と合わせるため、ポートは5051に固定している。
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const PORT = 5051;
const HOST = "localhost";
const ROOT = fileURLToPath(new URL(".", import.meta.url));

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

function reject(response, status, message) {
  response.writeHead(status, { "Content-Type": "text/plain; charset=utf-8", "X-Content-Type-Options": "nosniff" });
  response.end(message);
}

const server = createServer(async (request, response) => {
  try {
    if (request.method !== "GET" && request.method !== "HEAD") return reject(response, 405, "Method Not Allowed");
    if (!ALLOWED_HOSTS.has(request.headers.host ?? "")) return reject(response, 403, "Forbidden");

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

    const body = await readFile(filePath);
    response.writeHead(200, {
      "Content-Type": CONTENT_TYPES[extname(filePath).toLowerCase()] ?? "application/octet-stream",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff"
    });
    response.end(request.method === "HEAD" ? undefined : body);
  } catch {
    reject(response, 404, "Not Found");
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Frontend: http://${HOST}:${PORT}/index.html`);
});
