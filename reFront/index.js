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

// 「作品を知る」の動画は、日本時間の日付が奇数日ならプロモーション映像1、偶数日なら2（0:00に切り替え）
function promoVideoFor(now) {
  const day = new Date(now + 9 * 3600000).getUTCDate();
  return day % 2 === 1
    ? { src: "./video/pv/pv01.mp4", label: "プロモーション映像 1" }
    : { src: "./video/pv/pv02.mp4", label: "プロモーション映像 2" };
}

// 消音で自動再生する。動きを減らす設定では自動再生しない
const showcaseVideo = document.getElementById("showcase-video");
if (showcaseVideo) {
  const promo = promoVideoFor(Date.now());
  showcaseVideo.src = promo.src;
  showcaseVideo.setAttribute("aria-label", promo.label);
  const caption = document.getElementById("showcase-caption");
  if (caption) caption.textContent = `${promo.label}（約1分）`;
  showcaseVideo.preload = "metadata";
  if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    showcaseVideo.play().catch(() => {});
  }
}
