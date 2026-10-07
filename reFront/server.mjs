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
  ".ico": "image/x-icon"
};

const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, `http://${HOST}`).pathname);
    const relativePath = pathname.endsWith("/") ? `${pathname}index.html` : pathname;
    const filePath = normalize(join(ROOT, relativePath));
    if (!filePath.startsWith(ROOT)) throw new Error("outside of reFront");

    const body = await readFile(filePath);
    response.writeHead(200, {
      "Content-Type": CONTENT_TYPES[extname(filePath).toLowerCase()] ?? "application/octet-stream",
      "Cache-Control": "no-store"
    });
    response.end(body);
  } catch {
    response.writeHead(404).end("Not Found");
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Frontend: http://${HOST}:${PORT}/index.html`);
});
