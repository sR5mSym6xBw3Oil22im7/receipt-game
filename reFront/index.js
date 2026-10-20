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

// 試験用：URLのハッシュ（#notice=...）をお知らせ欄に表示する
const noticeBox = document.getElementById("notice-box");
if (noticeBox && location.hash.startsWith("#notice=")) {
  noticeBox.innerHTML = decodeURIComponent(location.hash.slice("#notice=".length));
}
