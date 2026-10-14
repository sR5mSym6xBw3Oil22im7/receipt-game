const isLocalFrontend = window.location.protocol === "file:"
  || window.location.hostname === ""
  || window.location.hostname === "localhost"
  || window.location.hostname === "127.0.0.1";

const backendBaseUrl = isLocalFrontend
  ? "http://localhost:8081"
  : "https://receipt-game.onrender.com";

const publicBaseUrl = isLocalFrontend
  ? "http://localhost:5051/index.html"
  : "https://sr5msym6xbw3oil22im7.github.io/receipt-game/reFront/index.html";

window.APP_CONFIG = {
  API_BASE_URL: backendBaseUrl,
  ADMIN_BASE_URL: `${backendBaseUrl}/admin`,
  DEMO_URL: `${backendBaseUrl}/demo/index.html`,
  GAME_URL: `${backendBaseUrl}/game/index.html`,
  SELECT_URL: `${backendBaseUrl}/admin/select.html`,
  PUBLIC_BASE_URL: publicBaseUrl
};
