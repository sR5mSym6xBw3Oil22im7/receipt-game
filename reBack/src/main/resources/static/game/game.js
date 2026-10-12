// モンスターレシート対戦の画面制御。
// ルーム・配布・勝敗はサーバー（/api/monster/rooms）が管理する。画面は状態を問い合わせて表示するだけ。
// 席トークンは sessionStorage に置き、ページを再読み込みしても同じ席に戻れるようにする。
(function () {
  "use strict";
  const API = "/api/monster/rooms";
  const $ = (id) => document.getElementById(id);
  // [名前, 1手の間隔ms, 必殺技カットインの長さms]
  const SPEEDS = [["ふつう", 1100, 1200], ["はやい", 450, 600], ["ゆっくり", 1900, 1200]];
  const SESSION_KEY = "monster-seat";
  const HAND_LIMIT = 3;

  const state = {
    seat: null, code: null, token: null, view: "lobby",
    room: null, field: null, myId: null, sub: "pool",
    pollTimer: null, replay: null, speedIndex: 0, battle: null, busy: false, revealedId: null, revealedShown: null
  };

  // ---------- 共通 ----------
  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }
  const yen = (n) => (n == null ? "" : "¥" + Number(n).toLocaleString("ja-JP"));
  const svgDataUri = (svg) => "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);

  function saveSession() {
    try { sessionStorage.setItem(SESSION_KEY, JSON.stringify({ code: state.code, token: state.token, seat: state.seat })); } catch (_) { /* 保存できなくても続ける */ }
  }
  function loadSession() {
    try { return JSON.parse(sessionStorage.getItem(SESSION_KEY) || "null"); } catch (_) { return null; }
  }
  function clearSession() {
    try { sessionStorage.removeItem(SESSION_KEY); } catch (_) { /* 何もしない */ }
    state.code = null; state.token = null; state.seat = null;
  }

  class ApiError extends Error {
    constructor(status, code, message) { super(message); this.status = status; this.code = code; }
  }

  async function api(path, options) {
    options = options || {};
    const headers = Object.assign({}, options.headers || {});
    if (state.token) headers["X-Seat-Token"] = state.token;
    let body = options.body;
    if (body !== undefined && !(body instanceof FormData)) { headers["Content-Type"] = "application/json"; body = JSON.stringify(body); }
    let res;
    try {
      res = await fetch(API + path, { method: options.method || "GET", headers, body, cache: "no-store", credentials: "same-origin" });
    } catch (_) {
      throw new ApiError(0, "NETWORK", "サーバーに接続できませんでした。通信状態を確認してください。");
    }
    if (res.status === 204) return null;
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const fallback = res.status === 429 ? "回数の上限に達しました。しばらく待ってから試してください。" : `エラーが発生しました（HTTP ${res.status}）。`;
      throw new ApiError(res.status, data.code || "", data.message || fallback);
    }
    return data;
  }

  function show(view) {
    fxClear();
    state.view = view;
    document.querySelectorAll(".view").forEach((s) => s.classList.toggle("hidden", s.id !== "view-" + view));
    const stepIndex = { wait: 0, select: 1, battle: 2, result: 3 }[view];
    if (stepIndex !== undefined) {
      document.querySelectorAll("#view-" + view + " .steps li").forEach((li, i) => {
        li.classList.toggle("is-done", i < stepIndex);
        li.classList.toggle("is-active", i === stepIndex);
        if (i === stepIndex) li.setAttribute("aria-current", "step"); else li.removeAttribute("aria-current");
      });
    }
    window.scrollTo({ top: 0 });
  }

  function poll(fn, ms) {
    stopPolling();
    const tick = async () => {
      try { await fn(); } catch (e) { handleError(e); }
      if (state.pollTimer !== null) state.pollTimer = setTimeout(tick, ms);
    };
    state.pollTimer = setTimeout(tick, ms);
  }
  function stopPolling() {
    if (state.pollTimer !== null) clearTimeout(state.pollTimer);
    state.pollTimer = null;
  }

  // 席が失われた・ルームがない場合はロビーへ戻す
  function handleError(e, statusEl) {
    if (e instanceof ApiError && (e.status === 403 || e.status === 404) && state.view !== "lobby") {
      backToLobby(e.message);
      return;
    }
    if (statusEl) { statusEl.textContent = e.message; statusEl.classList.add("is-error"); }
  }

  function setStatus(el, text, isError) {
    el.textContent = text || "";
    el.classList.toggle("is-error", !!isError);
  }

  // ---------- カード・レシートの表示 ----------
  function cardHtml(card, opt) {
    opt = opt || {};
    const art = `<div class="mc-art"><span class="mc-elem" title="属性">${esc(card.element)}</span>${card.lucky ? '<span class="mc-zoro">ラッキー</span>' : ""}<img alt="${esc(card.name)}のイラスト" src="${svgDataUri(card.svg)}"></div>`;
    const tag = opt.clickable ? "button" : "div";
    const cls = ["mcard", "e-" + card.element, "r-" + card.rarity, opt.selected ? "is-selected" : "", opt.clickable ? "is-clickable" : "", opt.revealed ? "is-revealed" : ""].join(" ");
    const attrs = opt.clickable ? ` type="button" data-card="${card.id}" aria-pressed="${opt.selected ? "true" : "false"}"` : "";
    const src = { ANALYZE: "保存済みレシート", PLAYER1: "P1が画像から生成", PLAYER2: "P2が画像から生成" }[card.source] || card.source;
    return `<${tag} class="${cls}"${attrs}>
      <div class="mc-head"><span class="mc-name">${esc(card.name)}</span><span class="mc-rarity">${esc(card.rarity)}</span></div>
      ${art}
      <dl class="mc-stats"><div><dt>HP</dt><dd>${card.hp}</dd></div><div><dt>ATK</dt><dd>${card.atk}</dd></div><div><dt>DEF</dt><dd>${card.def}</dd></div><div><dt>SPD</dt><dd>${card.spd}</dd></div><div><dt>LUCK</dt><dd>${card.luck}</dd></div></dl>
      <div class="mc-skill">必殺技：${esc(card.skillName)} ×${Number(card.skillPower).toFixed(2)}</div>
      <p class="mc-flavor">${esc(card.flavor)}</p>
      <div class="mc-foot">${esc(card.storeCategory)}のレシート<span class="src">${esc(src)}</span></div>
    </${tag}>`;
  }

  // レシートの表示項目（店舗カテゴリ・店名・日時・レシート番号・商品・合計・支払方法）。行テキストは表示しない。
  function receiptHtml(r, title) {
    if (!r) return '<p class="help-text">レシートの内容を表示できません。</p>';
    const items = (r.items || []).map((it) => `<tr><td>${esc(it.name)}</td><td class="q">${esc(it.quantity == null ? "" : it.quantity)}</td><td class="n">${yen(it.unitPrice)}</td><td class="n">${yen(it.amount)}</td></tr>`).join("");
    return `<span class="rt-no">${title ? esc(title) : ""} <em>${esc(r.storeCategory)}</em></span>
      <span class="rt-store">${esc(r.storeName || "（店名なし）")}${r.branchName ? " " + esc(r.branchName) : ""}</span>
      <span class="rt-meta">${esc(r.purchasedAt || "日時なし")}${r.receiptNumber ? "　レシート番号 " + esc(r.receiptNumber) : ""}</span>
      <table class="rt-items"><thead><tr><th>商品</th><th class="q">数</th><th class="n">単価</th><th class="n">金額</th></tr></thead><tbody>${items}</tbody></table>
      <span class="rt-total"><span>合計</span><b>${yen(r.totalAmount)}</b></span>
      <span class="rt-meta">お支払い：${esc(r.paymentMethod || "―")}</span>`;
  }


  // ---------- 演出（表示だけ。ゲームの結果・進行は変えない） ----------
  // 動きはCSS（game.css の fx- セクション）。ここでは「fx-on-* クラスの付け外し」と「演出用要素の生成と片付け」だけを行う。
  const ELEM_COLOR = { "炎": "#e4572e", "土": "#a7803f", "風": "#3fb596", "雷": "#e8b800", "無": "#8b8fa3" };
  const RARITY_COLOR = { R: "#3f7fbf", SR: "#8a4fbf", SSR: "#d99a00" };
  const SUMMON_MS = { N: 1200, R: 1600, SR: 3000, SSR: 4400 };
  const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const BOLT_SVG = '<svg class="fx-bolt" aria-hidden="true" viewBox="0 0 70 120" preserveAspectRatio="none"><polyline points="42,0 22,38 40,44 14,84 32,90 18,120" stroke="#fff" stroke-width="7"/><polyline points="42,0 22,38 40,44 14,84 32,90 18,120" stroke="#e8b800" stroke-width="3"/></svg>';
  const CRACK_SVG = '<svg viewBox="0 0 100 140" preserveAspectRatio="none" aria-hidden="true"><polyline points="62,0 54,26 66,44 48,70 60,92 44,118 50,140"/><polyline points="48,70 28,78 16,100"/><polyline points="66,44 86,52 96,74"/><polyline points="60,92 78,104 84,126"/></svg>';
  const CROWN_SVG = '<svg viewBox="0 0 64 40" aria-hidden="true"><path d="M4 36 L8 8 L22 22 L32 4 L42 22 L56 8 L60 36 Z" fill="#e2b443" stroke="#8a5f0b" stroke-width="3" stroke-linejoin="round"/><circle cx="8" cy="8" r="3.5" fill="#fff3c4" stroke="#8a5f0b" stroke-width="1.5"/><circle cx="32" cy="4" r="3.5" fill="#fff3c4" stroke="#8a5f0b" stroke-width="1.5"/><circle cx="56" cy="8" r="3.5" fill="#fff3c4" stroke="#8a5f0b" stroke-width="1.5"/></svg>';
  const fx = { timers: new Set(), code: null, opp: null, total: null };
  const reduced = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const rand = (a, b) => a + Math.random() * (b - a);

  function fxLater(fn, ms) {
    const t = setTimeout(() => { fx.timers.delete(t); fn(); }, ms);
    fx.timers.add(t);
    return t;
  }
  // 演出用の要素（読み上げ対象外）。片付けは fxClear が .fx-temp をまとめて消す
  function fxEl(parent, cls) {
    const el = document.createElement("div");
    el.className = "fx-temp " + cls;
    el.setAttribute("aria-hidden", "true");
    parent.append(el);
    return el;
  }
  // 親ごと消える子要素
  function fxChild(parent, cls, vars) {
    const el = document.createElement("div");
    el.className = cls;
    Object.keys(vars || {}).forEach((k) => el.style.setProperty(k, vars[k]));
    parent.append(el);
    return el;
  }
  function fxOn(el, name, ms) {
    if (!el) return;
    el.classList.add("fx-on-" + name);
    fxLater(() => el.classList.remove("fx-on-" + name), ms);
  }
  // 画面を切り替えるとき・再生を止めるときに、演出の要素・クラス・タイマーを残さない
  function fxClear() {
    fx.timers.forEach((t) => clearTimeout(t));
    fx.timers.clear();
    document.querySelectorAll(".fx-temp").forEach((el) => el.remove());
    $("fx-layer").textContent = "";   // 金貨の雨など、レイヤーに直接置いた要素も消す
    document.querySelectorAll('[class*="fx-on-"]').forEach((el) => {
      Array.from(el.classList).forEach((c) => { if (c.startsWith("fx-on-")) el.classList.remove(c); });
    });
    fx.code = null; fx.opp = null; fx.total = null;
  }
  function fxCenter(el) {
    const r = el.getBoundingClientRect();
    return { x: Math.min(Math.max(r.left + r.width / 2, 0), window.innerWidth), y: Math.min(Math.max(r.top + r.height / 2, 0), window.innerHeight) };
  }
  function fxCoins(parent, count, loop) {
    for (let i = 0; i < count; i++) {
      const c = fxChild(parent, "fx-coin", { "--dur": rand(1.6, 2.8).toFixed(2) + "s", "--delay": rand(0, loop ? 2.8 : 1.2).toFixed(2) + "s", "--dx": Math.round(rand(-40, 40)) + "px" });
      const size = Math.round(rand(12, 21));
      c.style.width = c.style.height = size + "px";
      c.style.left = rand(0, 100).toFixed(1) + "%";
      if (loop) c.style.animationIterationCount = "infinite";
    }
  }

  // ロビー・待機：ルームコードのドラム回転（コードが変わったときだけ）
  function fxRoomCode(code) {
    const el = $("room-code");
    el.setAttribute("aria-label", "ルームコード " + code);
    if (fx.code === code) return;
    fx.code = code;
    if (reduced()) { el.textContent = code; return; }
    el.textContent = "";
    Array.from(code).forEach((ch, i) => {
      const drum = document.createElement("span");
      drum.className = "fx-drum";
      drum.setAttribute("aria-hidden", "true");
      const strip = document.createElement("span");
      strip.className = "fx-drum-strip";
      strip.style.setProperty("--dur", (0.9 + 0.25 * i).toFixed(2) + "s");
      for (let k = 0; k < 9; k++) {
        const s = document.createElement("span");
        s.textContent = k < 8 ? CODE_CHARS[Math.floor(Math.random() * CODE_CHARS.length)] : ch;
        strip.append(s);
      }
      drum.append(strip);
      el.append(drum);
    });
  }
  function fxCopied() {
    const box = $("btn-copy").parentElement;
    box.querySelectorAll(".fx-copied").forEach((x) => x.remove());
    const el = fxEl(box, "fx-copied");
    el.textContent = "コピーしました";
    fxLater(() => el.remove(), 1700);
  }
  // 相手の席が「待機中」から変わった瞬間に一度だけ「参戦」（CPUなら「CPU」）ハンコ
  function fxSeatState(id, now) {
    const prev = fx.opp;
    fx.opp = now;
    if (prev === "empty" && now !== "empty") fxSeatJoined(id, now === "cpu");
  }
  function fxSeatJoined(id, cpu) {
    const li = $(id);
    li.querySelectorAll(".fx-seat-stamp, .fx-seat-dust").forEach((x) => x.remove());
    const stamp = fxEl(li, "fx-stamp fx-seat-stamp" + (cpu ? " is-blue" : ""));
    stamp.textContent = cpu ? "CPU" : "参戦";
    if (reduced()) return;
    fxOn(li, "slide", 1000);
    [[-30, -30], [30, -30], [-30, 30], [30, 30]].forEach(([dx, dy]) => {
      const d = fxEl(li, "fx-seat-dust");
      d.style.setProperty("--dx", dx + "px"); d.style.setProperty("--dy", dy + "px");
      fxLater(() => d.remove(), 1600);
    });
  }
  function fxWaitRing(room) {
    const ring = $("wait-ring");
    const waiting = room.status === "WAITING" && room.waitSecondsLeft != null;
    ring.classList.toggle("hidden", !waiting);
    if (!waiting) return;
    const left = Math.max(0, Number(room.waitSecondsLeft));
    if (fx.total === null) fx.total = Math.max(left, 1);   // 最初に受け取った秒数を全体とする
    const ratio = Math.min(left / fx.total, 1);
    $("wait-ring-bar").style.strokeDashoffset = String(100 * (1 - ratio));
    ring.classList.toggle("is-low", ratio <= 0.15);
    $("wait-ring-num").textContent = String(left);
  }

  // カード召喚：レシートがちぎれ、カードが現れ、レアリティ別の光とラッキーボーナスが出る
  const findCardEl = (card) => document.querySelector('[data-card="' + card.id + '"]');
  function fxSummon(card, tile) {
    const base = tile ? 350 : 100;
    if (!reduced()) {
      fxOn(tile, "tear", 450);
      fxLater(() => { const el = findCardEl(card); if (el) fxRarity(card.rarity, fxCenter(el)); }, base + 150);
      fxLater(() => {   // 手札のスロットを強調：一瞬大きくなり、金の輪が広がる
        const el = findCardEl(card);
        if (!el) return;
        fxOn(el, "pop", 500);
        const c = fxCenter(el);
        const ring = fxEl($("fx-layer"), "fx-ring-burst");
        ring.style.setProperty("--x", c.x + "px"); ring.style.setProperty("--y", c.y + "px");
        ring.style.setProperty("--c", "#c9971f"); ring.style.setProperty("--sz", "160px"); ring.style.setProperty("--ms", "0.5s");
        fxLater(() => ring.remove(), 600);
      }, base + 800);
    }
    if (card.lucky) fxLater(() => { const el = findCardEl(card); if (el) fxLucky(el); }, base + 700);
  }
  function fxRarity(rarity, c) {
    const layer = $("fx-layer");
    const color = RARITY_COLOR[rarity];
    if (!color) return;   // N は光なし
    const put = (cls, ms, vars) => {
      const el = fxEl(layer, cls);
      el.style.setProperty("--x", c.x + "px"); el.style.setProperty("--y", c.y + "px");
      Object.keys(vars || {}).forEach((k) => el.style.setProperty(k, vars[k]));
      fxLater(() => el.remove(), ms);
      return el;
    };
    if (rarity === "SSR") put("fx-flash", 700);
    else put("fx-ring-burst", 1100, { "--c": color });
    if (rarity === "SR" || rarity === "SSR") {
      put("fx-curtain", 2500);
      put("fx-pillar", 2300, { "--c": color, "--w": rarity === "SSR" ? "340px" : "220px" });
    }
    if (rarity === "SSR") {
      const coins = fxEl(layer, "fx-coins");
      fxCoins(coins, 28, false);
      fxLater(() => coins.remove(), 4400);
    }
  }
  // ラッキーボーナス：スロットが 7・7・7 でそろい、ハンコが押される
  function fxLucky(cardEl) {
    const r = cardEl.getBoundingClientRect();
    const g = fxEl($("fx-layer"), "fx-lucky");
    const side = window.innerWidth - r.right >= 170;
    g.style.setProperty("--x", (side ? r.right + 6 : r.left + r.width / 2) + "px");
    g.style.setProperty("--y", Math.min(Math.max(r.top + r.height / 2, 90), window.innerHeight - 90) + "px");
    g.style.setProperty("--tx", side ? "0" : "-50%");
    g.style.setProperty("--out", "4.6s");
    const slots = fxChild(g, "fx-slots");
    [0.9, 1.25, 1.6].forEach((dur) => {
      const win = fxChild(slots, "fx-slot");
      const strip = fxChild(win, "fx-slot-strip", { "--dur": dur + "s" });
      for (let k = 0; k < 8; k++) {
        const s = document.createElement("span");
        s.textContent = k < 7 ? String(Math.floor(Math.random() * 10)) : "7";
        strip.append(s);
      }
    });
    const stamp = fxChild(g, "fx-stamp fx-lucky-stamp");
    const t1 = document.createElement("span"); t1.textContent = "ラッキーボーナス";
    const t2 = document.createElement("small"); t2.textContent = "ATK・DEF ×1.10";
    stamp.append(t1, t2);
    fxLater(() => g.remove(), reduced() ? 3500 : 5400);
  }

  // 対戦：登場（戻り値は登場が終わるまでのms。止める設定なら0）
  function fxBattleIntro() {
    if (reduced()) return 0;
    fxOn($("fighter-0"), "in-left", 800);
    fxOn($("fighter-1"), "in-right", 800);
    fxOn($("battle-arena"), "intro", 1400);
    const ring = fxEl($("battle-arena").querySelector(".vs"), "fx-vs-ring");
    fxLater(() => ring.remove(), 1800);
    return 1200;
  }
  // ダメージ数字（ラベル付き）
  function fxDamage(e, defBox) {
    const f = fxEl(defBox, "float-dmg" + (e.crit ? " is-crit" : e.elem < 1 ? " is-weak" : ""));
    const label = e.crit ? "会心！" : e.skill ? "必殺技" : e.elem > 1 ? "属性有利" : e.elem < 1 ? "いまひとつ" : "";
    if (label) { const l = document.createElement("span"); l.className = "fx-dmg-label"; l.textContent = label; f.append(l); }
    const n = document.createElement("b");
    n.textContent = e.damage;
    f.append(n);
    fxLater(() => f.remove(), 1300);
  }
  // 攻撃する側の属性ごとのヒット演出と、会心の一撃
  function fxHit(e, attCard, defBox) {
    if (reduced()) return;
    const box = fxEl(defBox, "fx-hitfx");
    fxLater(() => box.remove(), 1500);
    if (attCard.element === "炎") {
      [22, 50, 76].forEach((l, i) => { const f = fxChild(box, "fx-flame", { "--d": (i * 0.12) + "s" }); f.style.left = l + "%"; });
    } else if (attCard.element === "風") {
      [0, 1, 2].forEach((i) => fxChild(box, "fx-wind", { "--d": (i * 0.12) + "s" }));
    } else if (attCard.element === "土") {
      [18, 38, 58, 76].forEach((l, i) => {
        const s = Math.round(rand(22, 32));
        const rk = fxChild(box, "fx-rock", { "--s": s + "px", "--d": (i * 0.07).toFixed(2) + "s", "--ty": Math.round(rand(170, 230)) + "px", "--r": Math.round(rand(120, 300)) + "deg" });
        rk.style.left = l + "%";
      });
      fxChild(box, "fx-sand");
    } else if (attCard.element === "雷") {
      box.insertAdjacentHTML("beforeend", BOLT_SVG);
      const flash = fxEl($("fx-layer"), "fx-screen-flash");
      fxLater(() => flash.remove(), 600);
    }
    if (e.crit) {
      const arena = $("battle-arena");
      const w = fxEl(arena, "fx-arena-flash");
      fxLater(() => w.remove(), 400);
      fxOn(arena, "hitstop", 350);
      fxChild(box, "fx-slash");
    }
  }
  // 必殺技のカットイン（文字はすべて textContent）
  function fxCutIn(card, e, ms) {
    const arena = $("battle-arena");
    const dim = fxEl(arena, "fx-dim");
    const band = fxEl(arena, "fx-cutin");
    [dim, band].forEach((x) => x.style.setProperty("--ms", ms + "ms"));
    band.style.setProperty("--el", ELEM_COLOR[card.element] || ELEM_COLOR["無"]);
    const art = document.createElement("img");
    art.className = "fx-cutin-art";
    art.alt = "";
    art.src = svgDataUri(card.svg);
    const text = document.createElement("div");
    text.className = "fx-cutin-text";
    const who = document.createElement("span"); who.className = "fx-cutin-who"; who.textContent = card.name + "の必殺技";
    const skill = document.createElement("span"); skill.className = "fx-cutin-skill"; skill.textContent = e.skillName;
    const power = document.createElement("span"); power.className = "fx-cutin-power"; power.textContent = "威力 ×" + Number(card.skillPower).toFixed(2);
    text.append(who, skill, power);
    band.append(art, text);
    fxLater(() => { dim.remove(); band.remove(); }, ms + 50);
  }
  // KO：ひびと「KO」ハンコ
  function fxKo(defBox) {
    const card = defBox.querySelector(".mcard");
    if (!card) return;
    const crack = fxEl(defBox, "fx-crack");
    crack.innerHTML = CRACK_SVG;
    crack.style.left = card.offsetLeft + "px"; crack.style.top = card.offsetTop + "px";
    crack.style.width = card.offsetWidth + "px"; crack.style.height = card.offsetHeight + "px";
    const ko = fxEl(defBox, "fx-stamp fx-ko");
    ko.textContent = "KO";
    ko.style.left = (card.offsetLeft + card.offsetWidth / 2) + "px";
    ko.style.top = (card.offsetTop + card.offsetHeight * 0.45) + "px";
  }

  // 結果：見出し・王冠・金貨の雨・バトル明細
  function fxResult(b, me, iWon, draw, text) {
    const banner = $("result-banner");
    banner.textContent = "";
    banner.classList.add("is-split");
    banner.setAttribute("role", "img");
    banner.setAttribute("aria-label", text);
    Array.from(text).forEach((ch, i) => {
      const s = document.createElement("span");
      s.className = "fx-ch";
      s.setAttribute("aria-hidden", "true");
      s.style.setProperty("--i", i);
      s.textContent = ch === " " ? " " : ch;
      banner.append(s);
    });
    if (!draw) {
      const winner = $("result-arena").querySelector(".fighter.is-winner");
      if (winner) fxEl(winner, "fx-crown").innerHTML = CROWN_SVG;
    }
    if (iWon && !reduced()) fxCoins($("fx-layer"), 30, true);
    fxSlip(b, me, iWon, draw);
  }
  function fxSlip(b, me, iWon, draw) {
    const slip = $("result-slip");
    slip.textContent = "";
    let acts = 0, dealt = 0, taken = 0, crits = 0, skills = 0;
    b.log.forEach((e) => {
      if (e.actor === me) { acts++; dealt += e.damage; if (e.crit) crits++; if (e.skill) skills++; } else taken += e.damage;
    });
    const rows = [["行動回数", acts + "回"], ["与えたダメージ", String(dealt)], ["受けたダメージ", String(taken)], ["会心の回数", crits + "回"], ["必殺技の回数", skills + "回"], ["決着", b.reason === "KO" ? "KO" : "20ラウンド終了（判定）"]];
    const title = document.createElement("p");
    title.className = "fx-slip-title";
    title.textContent = "バトル明細";
    slip.append(title);
    rows.forEach(([k, v], i) => {
      const row = document.createElement("div");
      row.className = "fx-slip-row" + (i === rows.length - 1 ? " is-total" : "");
      row.style.setProperty("--i", i);
      const key = document.createElement("span"); key.textContent = k;
      const val = document.createElement("b"); val.textContent = v;
      row.append(key, val);
      slip.append(row);
    });
    const stamp = document.createElement("div");
    stamp.className = "fx-stamp fx-slip-stamp" + (draw ? " is-draw" : iWon ? "" : " is-lose");
    stamp.setAttribute("aria-hidden", "true");
    stamp.textContent = draw ? "分" : iWon ? "勝" : "負";
    slip.append(stamp);
  }

  // ---------- M01 ロビー ----------
  function backToLobby(notice) {
    stopPolling();
    stopReplay();
    clearSession();
    state.room = null; state.field = null; state.myId = null; state.battle = null;
    const el = $("lobby-notice");
    el.textContent = notice || "";
    el.classList.toggle("hidden", !notice);
    show("lobby");
  }

  async function createRoom() {
    if (state.busy) return;
    state.busy = true;
    $("lobby-notice").classList.add("hidden");
    try {
      const res = await api("", { method: "POST" });
      adoptSeat(res);
      route(res.room);
    } catch (e) {
      $("lobby-notice").textContent = e.message;
      $("lobby-notice").classList.remove("hidden");
    } finally { state.busy = false; }
  }

  async function joinRoom() {
    const input = $("join-code");
    const raw = input.value.trim().toUpperCase();
    if (!/^[A-HJ-NP-Z2-9]{6}$/.test(raw)) {
      input.setAttribute("aria-invalid", "true");
      $("join-error").textContent = "6桁の英数字（I・O・0・1は使いません）を入力してください。";
      input.focus();
      return;
    }
    input.removeAttribute("aria-invalid");
    $("join-error").textContent = "";
    if (state.busy) return;
    state.busy = true;
    try {
      const res = await api("/" + raw + "/join", { method: "POST" });
      adoptSeat(res);
      route(res.room);
    } catch (e) {
      input.setAttribute("aria-invalid", "true");
      $("join-error").textContent = e.message;
    } finally { state.busy = false; }
  }

  function adoptSeat(res) {
    state.code = res.code; state.token = res.token; state.seat = res.seat;
    saveSession();
  }

  async function leaveRoom() {
    stopPolling();
    try { if (state.code) await api("/" + state.code + "/leave", { method: "POST" }); } catch (_) { /* 閉じられなくてもロビーへ戻る */ }
    backToLobby("");
  }

  // ルームの状態に合わせて画面を選ぶ
  function route(room) {
    state.room = room;
    if (room.status === "CLOSED") { backToLobby(room.notice || "ルームは終了しました。"); return; }
    if (room.status === "WAITING") { enterWait(room); return; }
    if (room.status === "SELECTING") { enterSelect(); return; }
    if (room.status === "BATTLE") { startBattle(); }
  }

  // ---------- M02 待機 ----------
  function setSeatRow(id, name, badge, empty) {
    const li = $(id);
    li.classList.toggle("is-empty", !!empty);
    li.querySelector(".seat-name").textContent = name;
    li.querySelector(".badge").textContent = badge;
  }

  function renderWait(room) {
    fxRoomCode(room.code);
    const mine = room.seat === "P1" ? "seat-p1" : "seat-p2", theirs = room.seat === "P1" ? "seat-p2" : "seat-p1";
    setSeatRow(mine, "あなた", "参加済み", false);
    if (room.mode === "CPU") setSeatRow(theirs, "コンピュータ（自動）", "CPU", false);
    else if (room.opponentJoined) setSeatRow(theirs, "相手プレイヤー", "参加済み", false);
    else setSeatRow(theirs, "参加を待っています…", "待機中", true);
    fxSeatState(theirs, room.mode === "CPU" ? "cpu" : room.opponentJoined ? "joined" : "empty");
    fxWaitRing(room);
    $("wait-status").textContent = room.status === "WAITING"
      ? `ルームコードを相手に伝えてください。あと ${room.waitSecondsLeft} 秒で、参加がなければコンピュータ（P2）との対戦になります。`
      : room.mode === "CPU" ? "相手が参加しなかったため、コンピュータ対戦になりました。カード選択へ進みます…" : "2人そろいました。カード選択へ進みます…";
  }

  function enterWait(room) {
    show("wait");
    renderWait(room);
    poll(async () => {
      const r = await api("/" + state.code);
      renderWait(r);
      if (r.status !== "WAITING") { stopPolling(); setTimeout(() => route(r), 1200); }
    }, 1000);
  }

  // ---------- M03 / M04 カード選択 ----------
  async function enterSelect() {
    stopReplay();
    state.myId = null;
    state.battle = null;
    show("select");
    $("seat-badge").textContent = "あなたは " + state.seat;
    switchSub("pool");
    setStatus($("gen-status"), "");
    setStatus($("pool-status"), "");
    $("gen-preview").innerHTML = "";
    $("btn-confirm").textContent = "このカードで決定";
    try {
      state.field = await api("/" + state.code + "/field");
      renderSelect();
      updateOpponent(await api("/" + state.code));
    } catch (e) { handleError(e, $("pool-status")); }
    poll(pollSelect, 1500);
  }

  async function pollSelect() {
    const r = await api("/" + state.code);
    if (r.status === "CLOSED") { backToLobby(r.notice); return; }
    if (state.field && r.round !== state.field.round) { enterSelect(); return; }   // 相手が再戦を始めた
    updateOpponent(r);
    if (r.battleReady) { stopPolling(); $("chosen-info").textContent = "両者のカードが決まりました。対戦開始！"; setTimeout(startBattle, 1000); }
  }

  function updateOpponent(r) {
    state.room = r;
    const cpu = r.mode === "CPU";
    const who = cpu ? "コンピュータ（P2）" : "相手";
    let text;
    if (r.opponentState === "CONFIRMED") text = `${who}：カードを確定しました（内容は対戦開始まで非公開）`;
    else if (cpu) text = r.youConfirmed ? `${who}：レシートを見て考えています…（あなたのカードは知らせずに選びます）` : `${who}：あなたの決定を待っています（保存済みのカードから選びます）`;
    else text = `${who}：選択中…`;
    $("opponent-state").textContent = text;
    $("unresponsive-note").classList.toggle("hidden", !r.opponentUnresponsive);
  }

  const handFull = () => state.field && state.field.hand.length >= HAND_LIMIT;

  function receiptTileHtml(entry) {
    const f = state.field;
    const flipped = entry.selected;
    const disabled = flipped || f.confirmed || handFull() || state.busy;
    return `<article class="receipt-tile ${flipped ? "is-flipped" : ""}" aria-label="レシート ${entry.index + 1}">
      ${receiptHtml(entry.receipt, "レシート No." + (entry.index + 1))}
      <button type="button" class="${flipped ? "secondary-button" : "primary-button"} small-button" data-flip="${entry.index}" ${disabled ? "disabled" : ""}>${flipped ? "モンスターを呼び出しました" : "▶ このレシートを選ぶ"}</button>
    </article>`;
  }

  function renderSelect() {
    const f = state.field;
    // 登場の演出は、同じカードにつき最初の描画でだけ付ける（選択などの再描画で再生し直さない）
    const revealedId = state.revealedId !== state.revealedShown ? state.revealedId : null;
    if (revealedId !== null) state.revealedShown = revealedId;
    if (!f) return;
    $("receipt-field").innerHTML = f.receipts.map(receiptTileHtml).join("");
    $("btn-shuffle").classList.toggle("hidden", !f.shuffleVisible);
    $("btn-shuffle").disabled = !f.canShuffle || state.busy;
    const imageLocked = f.confirmed || handFull();
    $("image-input").disabled = imageLocked;
    $("drop-zone").classList.toggle("is-disabled", imageLocked);
    document.querySelectorAll(".subtab").forEach((b) => { b.disabled = imageLocked; });
    $("draw-info").textContent = `保存済みのレシートからランダムに${f.receipts.length}枚を表示しています。`;
    $("hand-title").textContent = `手札（${f.hand.length} / ${f.handLimit}枚）— この中から1枚を選びます`;
    if (f.confirmed && f.selectedCardId) state.myId = f.selectedCardId;
    $("hand-grid").innerHTML = f.hand.length
      ? f.hand.map((c) => cardHtml(c, { clickable: !f.confirmed, selected: c.id === state.myId, revealed: c.id === revealedId })).join("")
      : '<div class="hand-slot">まだモンスターは現れていません<br><small>レシートを選ぶと、ここに現れます</small></div>';
    // 画像から作ったカードは1体だけ表示する（作り直すと置き換わる）
    if (f.imageCard) {
      $("gen-preview").innerHTML = `<div class="card-grid">${cardHtml(f.imageCard, { clickable: !f.confirmed, selected: f.imageCard.id === state.myId, revealed: f.imageCard.id === revealedId })}</div>`;
    }
    updateChosen();
  }

  function allMyCards() {
    const f = state.field;
    return f ? f.hand.concat(f.imageCard ? [f.imageCard] : []) : [];
  }

  function updateChosen() {
    const f = state.field;
    const c = allMyCards().find((x) => x.id === state.myId);
    if (f && f.confirmed) {
      $("chosen-info").textContent = c ? `決定：${c.name}。相手の確定を待っています…` : "決定済みです。";
      $("btn-confirm").disabled = true;
      $("btn-confirm").textContent = "決定済み";
      return;
    }
    $("chosen-info").textContent = c ? `選択中：${c.name}（${c.element}・${c.rarity}・POWER ${c.power}）`
      : f && f.hand.length ? "手札から、対戦に使う1枚を選んでください。" : "レシートを選んで、モンスターを呼び出してください。";
    $("btn-confirm").disabled = !c || state.busy;
  }

  function switchSub(name) {
    state.sub = name;
    document.querySelectorAll(".subtab").forEach((b) => {
      const on = b.dataset.sub === name;
      b.classList.toggle("is-active", on);
      b.setAttribute("aria-selected", on ? "true" : "false");
    });
    $("sub-pool").classList.toggle("hidden", name !== "pool");
    $("sub-image").classList.toggle("hidden", name !== "image");
  }

  async function withBusy(fn, statusEl) {
    if (state.busy) return;
    state.busy = true;
    renderSelect();
    try { await fn(); } catch (e) { handleError(e, statusEl); } finally { state.busy = false; renderSelect(); }
  }

  function shuffleReceipts() {
    return withBusy(async () => {
      state.field = await api("/" + state.code + "/shuffle", { method: "POST" });
      setStatus($("pool-status"), "レシートを選び直しました。");
    }, $("pool-status"));
  }

  // 登場の演出が終わるまで revealedId を残す（レアリティ演出の長さに合わせる）
  function revealCard(card) {
    state.revealedId = card.id;
    state.revealedShown = null;
    setTimeout(() => { if (state.revealedId === card.id) state.revealedId = null; }, reduced() ? 700 : SUMMON_MS[card.rarity] || 1200);
  }

  async function flipReceipt(index) {
    let card = null;
    await withBusy(async () => {
      const res = await api("/" + state.code + "/flip", { method: "POST", body: { index } });
      state.field = res.field;
      card = res.card;
      revealCard(card);
      setStatus($("pool-status"), `${res.card.name}が現れました！`);
    }, $("pool-status"));
    if (card) {
      const btn = document.querySelector('[data-flip="' + index + '"]');
      fxSummon(card, btn ? btn.closest(".receipt-tile") : null);
    }
  }

  async function onImagePicked(file) {
    if (!file) return;
    if (!/^image\/(jpeg|png)$/.test(file.type)) { setStatus($("gen-status"), "JPEGまたはPNGの画像を選んでください。", true); return; }
    if (file.size > 5 * 1024 * 1024) { setStatus($("gen-status"), "画像は5 MiB以下にしてください。", true); return; }
    if (state.field && (state.field.confirmed || handFull())) { setStatus($("gen-status"), "今は画像からカードを作れません。", true); return; }
    let card = null;
    await withBusy(async () => {
      setStatus($("gen-status"), "レシートを読み取って、カードを作っています…（20秒ほどかかることがあります）");
      const form = new FormData();
      form.append("file", file);
      const res = await api("/" + state.code + "/image-card", { method: "POST", body: form });
      state.field = res.field;
      state.myId = res.card.id;
      card = res.card;
      revealCard(card);
      setStatus($("gen-status"), res.message);
    }, $("gen-status"));
    if (card) fxSummon(card, null);
  }

  function confirmCard() {
    const id = state.myId;
    if (!allMyCards().some((c) => c.id === id)) return;
    return withBusy(async () => {
      const r = await api("/" + state.code + "/select", { method: "POST", body: { cardId: id } });
      state.field = await api("/" + state.code + "/field");
      updateOpponent(r);
      if (r.battleReady) { stopPolling(); setTimeout(startBattle, 1000); }
    }, $("pool-status"));
  }

  // ---------- M05 対戦 ----------
  function fighterHtml(card, label) {
    return `<div class="hp-box"><div class="hp-label"><span>${esc(label)}</span><span class="hp-num">${card.hp} / ${card.hp}</span></div><div class="hp-bar"><i class="hp-ghost"></i><i class="hp-main"></i></div></div>${cardHtml(card)}`;
  }

  async function startBattle() {
    stopPolling();
    let battle;
    try { battle = await api("/" + state.code + "/battle"); } catch (e) { handleError(e, $("pool-status")); return; }
    state.battle = battle;
    show("battle");
    const me = state.seat, opp = me === "P1" ? "P2" : "P1";
    const cpu = battle.mode === "CPU";
    const slotOf = (seat) => (seat === me ? 0 : 1);   // 画面の左＝自分、右＝相手
    const slotCards = [battle.cards[me], battle.cards[opp]];
    state.replay = { battle, slotOf, slotCards, step: 0, timer: null };
    $("battle-seed").textContent = "battleSeed " + battle.battleSeed;
    $("cpu-note").classList.toggle("hidden", !cpu);
    $("cpu-note").textContent = cpu ? "コンピュータ（P2）の選んだ理由：" + (battle.cpuReason || "自動で選びました") : "";
    $("fighter-0").innerHTML = fighterHtml(slotCards[0], "あなた");
    $("fighter-1").innerHTML = fighterHtml(slotCards[1], cpu ? "コンピュータ" : "相手");
    ["fighter-0", "fighter-1"].forEach((id) => { $(id).className = "fighter"; });
    $("fighter-0").style.setProperty("--lunge", "40px");
    $("fighter-1").style.setProperty("--lunge", "-40px");
    $("fighter-0").style.setProperty("--tilt", "3deg");
    $("fighter-1").style.setProperty("--tilt", "-3deg");
    $("battle-log").innerHTML = "";
    const first = slotOf(battle.first);
    appendLog(`先攻：${slotCards[first].name}（素早さ ${slotCards[first].spd}）`);
    // 登場の演出が終わってから最初の playStep を始める
    const r = state.replay;
    const begin = () => { r.started = true; startTicker(r); };
    const wait = fxBattleIntro();
    if (wait > 0) fxLater(begin, wait); else begin();
  }

  function startTicker(r) {
    clearInterval(r.timer);
    r.timer = setInterval(() => playStep(), SPEEDS[state.speedIndex][1]);
  }

  function appendLog(text, who, name, tags) {
    const li = document.createElement("li");
    if (who !== undefined) {
      const s = document.createElement("span");
      s.className = "who" + who;
      s.textContent = name;
      li.append(s, document.createTextNode(text));
    } else li.textContent = text;
    (tags || []).forEach((t) => { const s = document.createElement("span"); s.className = "tag"; s.textContent = t; li.append(s); });
    $("battle-log").append(li);
    $("battle-log").scrollTop = $("battle-log").scrollHeight;
  }

  function setHp(slot, hp, max) {
    const box = $("fighter-" + slot);
    box.querySelector(".hp-num").textContent = `${hp} / ${max}`;
    const bar = box.querySelector(".hp-bar > i.hp-main");
    const ratio = hp / max;
    bar.style.width = (ratio * 100) + "%";
    box.querySelector(".hp-bar > i.hp-ghost").style.width = (ratio * 100) + "%";   // 赤い残像は遅れて追いつく
    bar.className = "hp-main " + (ratio <= 0.25 ? "is-low" : ratio <= 0.5 ? "is-mid" : "");
  }

  function playStep(instant) {
    const r = state.replay;
    if (!r || r.pending) return;   // 必殺技カットインの間は進めない
    const log = r.battle.log;
    if (r.step >= log.length) { finishBattle(); return; }
    const e = log[r.step++];
    const fast = instant === true;
    if (!fast && e.skill && !reduced()) {
      // カットインの間はタイマーを止め、終わってからダメージを表示して再開する
      clearInterval(r.timer);
      const att = r.slotOf(e.actor);
      fxCutIn(r.slotCards[att], e, SPEEDS[state.speedIndex][2]);
      const run = (skip) => {
        if (r.pending !== run) return;
        r.pending = null;
        applyStep(r, e, skip === true);
        if (skip !== true && state.replay === r && r.step < log.length) startTicker(r);
      };
      r.pending = run;
      fxLater(() => run(), SPEEDS[state.speedIndex][2]);
      return;
    }
    applyStep(r, e, fast);
  }

  function applyStep(r, e, fast) {
    const log = r.battle.log;
    const att = r.slotOf(e.actor), def = r.slotOf(e.target);
    setHp(def, e.hpAfter, r.slotCards[def].hp);
    const attBox = $("fighter-" + att), defBox = $("fighter-" + def);
    if (!fast) {
      attBox.classList.remove("is-attacking"); defBox.classList.remove("is-hit");
      void attBox.offsetWidth;
      attBox.classList.add("is-attacking"); defBox.classList.add("is-hit");
      fxDamage(e, defBox);
      fxHit(e, r.slotCards[att], defBox);
    }
    const tags = [];
    if (e.skill) tags.push("必殺技");
    if (e.elem > 1) tags.push("属性有利");
    if (e.elem < 1) tags.push("属性不利");
    if (e.crit) tags.push("会心！");
    appendLog(`の${e.skillName}！ ${e.damage}ダメージ`, att, r.slotCards[att].name, tags);
    if (e.hpAfter === 0) { defBox.classList.add("is-defeated"); if (!fast) fxKo(defBox); }
    if (r.step >= log.length) { clearInterval(r.timer); if (!fast) fxLater(finishBattle, 1300); }
  }

  function skipBattle() {
    const r = state.replay;
    if (!r) return;
    clearInterval(r.timer);
    if (r.pending) r.pending(true);   // カットイン待ちの1手は演出なしで反映する
    while (r.step < r.battle.log.length) playStep(true);
    finishBattle();
  }

  function stopReplay() {
    if (state.replay) clearInterval(state.replay.timer);
    state.replay = null;
    fxClear();
  }

  // ---------- M06 結果 ----------
  function finishBattle() {
    const r = state.replay;
    if (!r) return;
    stopReplay();
    const b = r.battle;
    show("result");
    const me = state.seat, opp = me === "P1" ? "P2" : "P1";
    const cpu = b.mode === "CPU";
    const draw = b.winner === "DRAW";
    const iWon = b.winner === me;
    const banner = $("result-banner");
    const bannerText = draw ? "DRAW" : iWon ? "YOU WIN!" : "YOU LOSE…";
    banner.className = "result-banner " + (draw ? "is-draw" : iWon ? "is-win" : "is-lose");
    const winnerName = draw ? "" : b.cards[b.winner].name;
    $("result-detail").textContent = (cpu ? "【コンピュータ対戦】" : "") + (draw ? "引き分けです。" : `${winnerName}の勝利！`) +
      (b.reason === "KO" ? `（KO決着・${b.log.length}回の行動）` : "（20ラウンド終了：残りHP割合で判定）") +
      ` battleSeed：${b.battleSeed}`;
    setStatus($("result-status"), "");
    const mineCls = draw ? "is-draw" : iWon ? "is-winner" : "is-defeated";
    const oppCls = draw ? "is-draw" : iWon ? "is-defeated" : "is-winner";
    $("result-arena").innerHTML = `<div class="fighter ${mineCls}">${cardHtml(b.cards[me])}</div><div class="vs" aria-hidden="true">VS</div><div class="fighter ${oppCls}">${cardHtml(b.cards[opp])}</div>`;
    $("result-receipts").innerHTML = [[me, "あなた"], [opp, cpu ? "コンピュータ" : "相手"]]
      .map(([seat, label]) => `<article class="receipt-tile"><h3>${esc(label)}のカードの元レシート</h3>${receiptHtml(b.receipts[seat])}</article>`).join("");
    $("btn-rematch").disabled = false;
    fxResult(b, me, iWon, draw, bannerText);
    // 相手が再戦を始めた・ルームを閉じたことに気づけるよう、状態を見続ける
    poll(async () => {
      const room = await api("/" + state.code);
      if (room.status === "CLOSED") { stopPolling(); setStatus($("result-status"), room.notice || "ルームは終了しました。", true); $("btn-rematch").disabled = true; return; }
      if (room.round !== b.round) { stopPolling(); enterSelect(); }
    }, 2000);
  }

  async function rematch() {
    if (!state.battle) return;
    $("btn-rematch").disabled = true;
    try {
      const room = await api("/" + state.code + "/rematch", { method: "POST", body: { round: state.battle.round } });
      stopPolling();
      route(room);
    } catch (e) {
      $("btn-rematch").disabled = false;
      handleError(e, $("result-status"));
    }
  }

  // ---------- 初期化 ----------
  async function resume() {
    const saved = loadSession();
    if (!saved || !saved.token) { show("lobby"); return; }
    state.code = saved.code; state.token = saved.token; state.seat = saved.seat;
    try { route(await api("/" + state.code)); } catch (e) { backToLobby(""); }
  }

  function init() {
    $("btn-create").addEventListener("click", createRoom);
    $("btn-join").addEventListener("click", joinRoom);
    $("join-code").addEventListener("keydown", (ev) => { if (ev.key === "Enter") joinRoom(); });
    $("join-code").addEventListener("input", () => { $("join-code").removeAttribute("aria-invalid"); $("join-error").textContent = ""; });
    $("btn-copy").addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(state.code); $("btn-copy").textContent = "コピー済み"; fxCopied(); } catch (_) { $("btn-copy").textContent = "コピーできません"; }
      setTimeout(() => { $("btn-copy").textContent = "コピー"; }, 1500);
    });
    $("btn-leave-wait").addEventListener("click", leaveRoom);
    document.querySelectorAll("[data-leave]").forEach((b) => b.addEventListener("click", leaveRoom));

    document.querySelectorAll(".subtab").forEach((b) => b.addEventListener("click", () => switchSub(b.dataset.sub)));
    $("btn-shuffle").addEventListener("click", shuffleReceipts);
    $("receipt-field").addEventListener("click", (ev) => {
      const b = ev.target.closest("[data-flip]");
      if (b && !b.disabled) flipReceipt(Number(b.dataset.flip));
    });
    const pickCard = (ev) => {
      const b = ev.target.closest("[data-card]");
      if (!b || !state.field || state.field.confirmed) return;
      state.myId = Number(b.dataset.card);
      renderSelect();
    };
    $("hand-grid").addEventListener("click", pickCard);
    $("gen-preview").addEventListener("click", pickCard);
    $("btn-confirm").addEventListener("click", confirmCard);
    $("image-input").addEventListener("change", (ev) => { onImagePicked(ev.target.files[0]); ev.target.value = ""; });
    const drop = $("drop-zone");
    ["dragover", "dragenter"].forEach((n) => drop.addEventListener(n, (ev) => { ev.preventDefault(); drop.classList.add("is-over"); }));
    ["dragleave", "drop"].forEach((n) => drop.addEventListener(n, () => drop.classList.remove("is-over")));
    drop.addEventListener("drop", (ev) => { ev.preventDefault(); if (!$("image-input").disabled) onImagePicked(ev.dataTransfer.files[0]); });

    $("btn-speed").addEventListener("click", () => {
      state.speedIndex = (state.speedIndex + 1) % SPEEDS.length;
      $("btn-speed").textContent = "速度：" + SPEEDS[state.speedIndex][0];
      const r = state.replay;
      if (r && r.started && !r.pending && r.step < r.battle.log.length) startTicker(r);
    });
    $("btn-skip").addEventListener("click", skipBattle);
    $("btn-rematch").addEventListener("click", rematch);

    resume();
  }

  init();
})();
