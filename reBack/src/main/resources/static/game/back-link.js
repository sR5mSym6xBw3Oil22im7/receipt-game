// 「メニューへ戻る」のリンク先を admin/config.js の PUBLIC_BASE_URL（login.html と同じ）に合わせる。
// config.js は金貨の装飾も描画するため、ここでは読み込まず同じ判定だけを行う。
(() => {
  const link = document.getElementById("back-link");
  if (!link) return;
  const host = window.location.hostname;
  const isLocal = window.location.protocol === "file:" || host === "" || host === "localhost" || host === "127.0.0.1";
  link.href = isLocal
    ? "http://localhost:5051/index.html"
    : "https://sr5msym6xbw3oil22im7.github.io/receipt-analysis/reFront/index.html";
})();
