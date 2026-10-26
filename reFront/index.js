const demoLink = document.getElementById("demo-link");
if (demoLink && window.APP_CONFIG?.DEMO_URL) {
  demoLink.href = window.APP_CONFIG.DEMO_URL;
}

const gameLink = document.getElementById("game-link");
if (gameLink && window.APP_CONFIG?.GAME_URL) {
  gameLink.href = window.APP_CONFIG.GAME_URL;
}

const adminLoginLink = document.getElementById("admin-login-link");
if (adminLoginLink && window.APP_CONFIG?.ADMIN_BASE_URL) {
  adminLoginLink.href = `${window.APP_CONFIG.ADMIN_BASE_URL}/login.html`;
}

// メニュー冒頭の紹介動画は、動きを減らす設定でなければ自動再生する（音声なし）
const showcaseVideo = document.getElementById("showcase-video");
if (showcaseVideo && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
  showcaseVideo.play().catch(() => {});
}
