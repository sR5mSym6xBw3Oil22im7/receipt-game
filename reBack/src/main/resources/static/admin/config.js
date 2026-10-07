const isLocalFrontend = window.location.protocol === "file:"
  || window.location.hostname === ""
  || window.location.hostname === "localhost"
  || window.location.hostname === "127.0.0.1";

const backendBaseUrl = isLocalFrontend
  ? "http://localhost:8081"
  : "https://receipt-analysis-b8po.onrender.com";

const publicBaseUrl = isLocalFrontend
  ? "http://localhost:5500/index.html"
  : "https://sr5msym6xbw3oil22im7.github.io/receipt-analysis/reFront/index.html";

window.APP_CONFIG = {
  API_BASE_URL: backendBaseUrl,
  ADMIN_BASE_URL: `${backendBaseUrl}/admin`,
  SELECT_URL: `${backendBaseUrl}/admin/select.html`,
  PUBLIC_BASE_URL: publicBaseUrl
};

// ---- 装飾（降り注ぐ金貨） ----
// ログイン画面は未ログインでも読める config.js しか使えないため、装飾もここに置く。
// 金貨・光の帯・きらめきを描画する共通スクリプト。
// 参考画像はそのまま使わず、すべてSVGで描き起こしている。
(() => {
  const NS = "http://www.w3.org/2000/svg";

  // ---- 色 ----
  const P = {
    bg: ["#fffaea", "#f5dd96", "#dcb052"],
    coin: ["#fff4c0", "#ecbd48", "#b07c14", "#6f4805"],
    glow: "#fff6cf",
    spark: "#ffffff"
  };

  // ---- 乱数（毎回同じ絵になるよう種を固定） ----
  function rng(seed) {
    let s = seed >>> 0;
    return () => {
      s = (s * 1664525 + 1013904223) >>> 0;
      return s / 4294967296;
    };
  }
  const n1 = (v) => (Math.round(v * 10) / 10).toString();
  const rad = (deg) => (deg * Math.PI) / 180;
  const pt = (r, deg) => [r * Math.cos(rad(deg)), r * Math.sin(rad(deg))];

  // ---- 金貨（太陽の紋章） ----
  function coinFace() {
    let rays = "";
    for (let i = 0; i < 8; i++) {
      rays += `<polygon points="0,-33 4,-11 -4,-11" transform="rotate(${i * 45})"/>`;
      rays += `<polygon points="0,-24 2.6,-9 -2.6,-9" transform="rotate(${i * 45 + 22.5})"/>`;
    }
    let dots = "";
    for (let i = 0; i < 36; i++) {
      const [x, y] = pt(44.5, i * 10);
      dots += `<circle cx="${n1(x)}" cy="${n1(y)}" r="1.1"/>`;
    }
    return `<circle r="49" fill="url(#gd-rim)"/>
      <circle r="49" fill="none" stroke="${P.coin[3]}" stroke-opacity=".7" stroke-width="1"/>
      <g fill="${P.coin[0]}" opacity=".85">${dots}</g>
      <circle r="40.5" fill="url(#gd-face)" stroke="${P.coin[3]}" stroke-opacity=".55" stroke-width="1.2"/>
      <circle r="37" fill="none" stroke="${P.coin[0]}" stroke-opacity=".6" stroke-width=".8"/>
      <g fill="${P.coin[2]}" stroke="${P.coin[0]}" stroke-opacity=".8" stroke-width=".7" stroke-linejoin="round">${rays}</g>
      <circle r="9" fill="url(#gd-face)" stroke="${P.coin[3]}" stroke-opacity=".6" stroke-width="1"/>
      <circle r="4" fill="${P.coin[2]}"/>
      <path d="M-30-24A40 40 0 0 1 10-39" fill="none" stroke="#fff" stroke-opacity=".75" stroke-width="2.4" stroke-linecap="round"/>`;
  }

  // ---- 共通シンボルとグラデーション ----
  const sprite = `
<svg xmlns="${NS}" width="0" height="0" style="position:absolute;width:0;height:0;overflow:hidden" aria-hidden="true" focusable="false">
  <defs>
    <linearGradient id="gd-rim" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="${P.coin[0]}"/><stop offset=".45" stop-color="${P.coin[1]}"/><stop offset="1" stop-color="${P.coin[3]}"/>
    </linearGradient>
    <linearGradient id="gd-face" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="${P.coin[0]}"/><stop offset=".5" stop-color="${P.coin[1]}"/><stop offset="1" stop-color="${P.coin[2]}"/>
    </linearGradient>
    <radialGradient id="gd-glow">
      <stop offset="0" stop-color="#fffef6"/><stop offset=".3" stop-color="${P.glow}" stop-opacity=".85"/><stop offset="1" stop-color="${P.glow}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="gd-bokeh">
      <stop offset="0" stop-color="${P.glow}" stop-opacity=".55"/><stop offset=".8" stop-color="${P.glow}" stop-opacity=".35"/><stop offset="1" stop-color="${P.glow}" stop-opacity="0"/>
    </radialGradient>
    <filter id="gd-blur3" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="3.5"/></filter>
    <filter id="gd-blur8" x="-60%" y="-60%" width="220%" height="220%"><feGaussianBlur stdDeviation="9"/></filter>
    <filter id="gd-blur20" x="-60%" y="-60%" width="220%" height="220%"><feGaussianBlur stdDeviation="22"/></filter>
  </defs>
  <symbol id="spark" viewBox="-50 -50 100 100">
    <circle r="16" fill="url(#gd-glow)"/>
    <path d="M0-50C3-10 10-3 50 0C10 3 3 10 0 50C-3 10-10 3-50 0C-10-3-3-10 0-50Z" fill="${P.spark}"/>
    <path d="M0-24C1.6-4 4-1.6 24 0C4 1.6 1.6 4 0 24C-1.6 4-4 1.6-24 0C-4-1.6-1.6-4 0-24Z" fill="${P.spark}" transform="rotate(45)" opacity=".7"/>
  </symbol>
  <symbol id="coin" viewBox="-50 -50 100 100">${coinFace()}</symbol>
  <symbol id="gd" viewBox="-50 -50 100 100"><use href="#coin" x="-48" y="-48" width="96" height="96"/></symbol>
</svg>`;
  document.body.insertAdjacentHTML("afterbegin", sprite);

  // ---- 部品を置くための小さな関数 ----
  // 傾いた金貨（厚みのある縁を下に描く）
  const coinAt = (cx, cy, size, rot = 0, squash = 1) => {
    const th = size * (0.03 + (1 - squash) * 0.07);
    return `<g transform="translate(${n1(cx)} ${n1(cy)}) rotate(${n1(rot)})">
      <ellipse cx="0" cy="${n1(th)}" rx="${n1(size / 2)}" ry="${n1((size / 2) * squash)}" fill="${P.coin[3]}"/>
      <use href="#coin" x="${n1(-size / 2)}" y="${n1(-size / 2)}" width="${n1(size)}" height="${n1(size)}" transform="scale(1 ${n1(squash * 100) / 100})"/></g>`;
  };
  const sparkAt = (cx, cy, size, delay) =>
    `<use class="twinkle" style="animation-delay:${n1(delay)}s" href="#spark" x="${n1(cx - size / 2)}" y="${n1(cy - size / 2)}" width="${n1(size)}" height="${n1(size)}"/>`;
  const bokeh = (rand, n, W, H, min, max, y0 = 0) => {
    let s = "";
    for (let i = 0; i < n; i++) s += `<circle cx="${n1(rand() * W)}" cy="${n1(y0 + rand() * H)}" r="${n1(min + rand() * (max - min))}" fill="url(#gd-bokeh)"/>`;
    return s;
  };
  const dust = (rand, n, x0, W, y0, H, color = P.coin[0]) => {
    let s = "";
    for (let i = 0; i < n; i++) s += `<circle cx="${n1(x0 + rand() * W)}" cy="${n1(y0 + rand() * H)}" r="${n1(0.6 + rand() * 1.8)}" fill="${color}" opacity="${n1(0.4 + rand() * 0.6)}"/>`;
    return s;
  };
  // 螺旋を描いて降りてくる光の帯
  const spiral = (cx, y0, y1, amp, turns, width = 3) => {
    let d = "";
    const N = 120;
    for (let i = 0; i <= N; i++) {
      const t = i / N;
      const x = cx + Math.sin(t * turns * Math.PI * 2) * amp * (0.45 + t * 0.55);
      const y = y0 + (y1 - y0) * t;
      d += `${i ? "L" : "M"}${n1(x)} ${n1(y)}`;
    }
    return `<path d="${d}" fill="none" stroke="#fff8d8" stroke-width="${width * 3}" stroke-opacity=".35" filter="url(#gd-blur3)"/><path d="${d}" fill="none" stroke="#fffdf2" stroke-width="${width}" stroke-opacity=".9" stroke-linecap="round"/>`;
  };
  const vgrad = (id, c) =>
    `<linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${c[0]}"/><stop offset=".55" stop-color="${c[1]}"/><stop offset="1" stop-color="${c[2]}"/></linearGradient>`;

  // 積み上がった金貨の山
  function coinPile(rand, cx, base, w, h, n, size) {
    let s = `<ellipse cx="${cx}" cy="${base}" rx="${w * 0.7}" ry="${h * 0.5}" fill="url(#gd-glow)" opacity=".8"/>`;
    const coins = [];
    for (let i = 0; i < n; i++) {
      const t = rand();
      const x = cx + (rand() - 0.5) * w * (1 - t * 0.7);
      const y = base - t * h + rand() * 6;
      coins.push([x, y, size * (0.75 + rand() * 0.4), (rand() - 0.5) * 40, 0.3 + rand() * 0.25]);
    }
    coins.sort((a, b) => a[1] - b[1]);
    for (const c of coins) s += coinAt(...c);
    return s;
  }

  // ---- 見出し用の絵（縦長カード・丸窓）の中身 ----
  const COIN = {
    hero: (W, H) => {
      const rand = rng(12);
      let s = `<rect x="110" y="0" width="80" height="${H}" fill="url(#gd-glow)" opacity=".6" filter="url(#gd-blur8)"/>`;
      s += bokeh(rand, 10, W, H, 6, 18);
      s += dust(rand, 120, 60, 180, 20, 330);
      s += spiral(150, 10, 340, 80, 2.6, 1.6);
      const falling = [[150, 40, 46, -20, 0.7], [96, 96, 40, 30, 0.5], [206, 120, 52, -10, 0.85], [130, 176, 58, 16, 0.6], [214, 220, 44, -30, 0.45], [86, 236, 40, 40, 0.75], [160, 262, 50, -6, 0.55], [238, 60, 30, 10, 0.6], [60, 160, 30, -20, 0.8]];
      for (const c of falling) s += coinAt(...c);
      s += coinPile(rand, 150, 400, 210, 70, 34, 44);
      s += sparkAt(150, 330, 40, 0) + sparkAt(240, 160, 22, 1.4) + sparkAt(56, 80, 20, 2.1) + sparkAt(190, 30, 18, 0.8);
      return s;
    },
    small: (S, v) => {
      const rand = rng(4);
      return `${dust(rand, 30, 20, 120, 10, 140)}${v === "tile" ? "" : coinAt(124, 40, 34, -20, 0.55) + coinAt(36, 120, 30, 30, 0.6)}${coinAt(80, 84, v === "tile" ? 112 : 100, -8, 0.9)}${sparkAt(118, 116, 26, 0.6)}`;
    }
  };

  let uid = 0;
  const nextId = () => `m${++uid}`;

  // ---- 見出し用の絵（縦長カード・丸窓）を組み立てる ----
  function frame(el, inner) {
    const variant = el.dataset.motif || "small";
    const id = nextId();
    const lightLayer = `<ellipse cx="50%" cy="20%" rx="60%" ry="40%" fill="url(#gd-glow)" opacity=".7"/>`;
    const border = `<linearGradient id="${id}b" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff3c4"/><stop offset=".4" stop-color="#d9a73a"/><stop offset=".7" stop-color="#9c6a0e"/><stop offset="1" stop-color="#f3d27a"/></linearGradient>`;
    if (variant === "hero") {
      const W = 300, H = 420;
      el.innerHTML = `<svg viewBox="0 0 ${W} ${H}" xmlns="${NS}" aria-hidden="true" focusable="false">
        <defs>${vgrad(`${id}g`, P.bg)}<clipPath id="${id}c"><rect width="${W}" height="${H}" rx="26"/></clipPath>${border}</defs>
        <g clip-path="url(#${id}c)"><rect width="${W}" height="${H}" fill="url(#${id}g)"/>${lightLayer}${inner.hero(W, H)}</g>
        <rect x="2" y="2" width="${W - 4}" height="${H - 4}" rx="24" fill="none" stroke="url(#${id}b)" stroke-width="4"/>
        <rect x="9" y="9" width="${W - 18}" height="${H - 18}" rx="18" fill="none" stroke="#fff6d6" stroke-opacity=".7" stroke-width="1"/>
      </svg>`;
      return;
    }
    const S = 160;
    el.innerHTML = `<svg viewBox="0 0 ${S} ${S}" xmlns="${NS}" aria-hidden="true" focusable="false">
      <defs>${vgrad(`${id}g`, P.bg)}<clipPath id="${id}c"><circle cx="80" cy="80" r="77"/></clipPath>${border}</defs>
      <g clip-path="url(#${id}c)"><rect width="${S}" height="${S}" fill="url(#${id}g)"/>${lightLayer}${inner.small(S, variant)}</g>
      <circle cx="80" cy="80" r="77" fill="none" stroke="url(#${id}b)" stroke-width="4"/>
    </svg>`;
  }

  // 背景レイヤーを置く
  function scene(svgInner) {
    const layer = document.createElement("div");
    layer.className = "scene";
    layer.setAttribute("aria-hidden", "true");
    layer.innerHTML = `<svg viewBox="0 0 1600 1000" preserveAspectRatio="xMidYMid slice" xmlns="${NS}">${svgInner}</svg>`;
    document.body.prepend(layer);
  }

  // 背景：上からの光の柱、螺旋の光の帯、舞う金貨、左右の金貨の山
  function coinScene() {
    const rand = rng(33);
    let s = `<ellipse cx="800" cy="-60" rx="560" ry="520" fill="url(#gd-glow)" opacity=".9"/>`;
    s += `<rect x="640" y="0" width="320" height="1000" fill="url(#gd-glow)" opacity=".45" filter="url(#gd-blur20)"/>`;
    s += bokeh(rand, 30, 1600, 1000, 10, 46);
    s += dust(rand, 260, 120, 1360, 0, 1000);
    s += spiral(800, -40, 980, 640, 2.2, 2.4);
    for (let i = 0; i < 26; i++) {
      const side = i % 2 ? 1 : -1;
      const x = 800 + side * (330 + rand() * 470);
      s += coinAt(x, rand() * 900, 36 + rand() * 90, (rand() - 0.5) * 70, 0.4 + rand() * 0.55);
    }
    s += `<g filter="url(#gd-blur3)">${coinAt(1500, 120, 130, 20, 0.6)}${coinAt(110, 380, 120, -30, 0.5)}</g>`;
    s += coinPile(rand, 140, 1010, 420, 140, 60, 80) + coinPile(rand, 1470, 1010, 420, 130, 60, 80);
    scene(s);
  }

  document.querySelectorAll("[data-motif]").forEach((el) => frame(el, COIN));
  coinScene();
})();
