// モンスターレシート対戦の演出（本番 /game/ とデモ /demo/ で共通）。表示だけで、ゲームの結果・進行は変えない。
// 動きはCSS（fx.css）。ここでは「fx-on-* クラスの付け外し」と「演出用要素の生成と片付け」だけを行う。
// 画面の要素とデータ（カード・ログ）は引数で受け取り、game.js・demo.js の変数にはさわらない。
(function () {
  "use strict";
  const ELEM_COLOR = { "炎": "#e4572e", "土": "#a7803f", "風": "#3fb596", "雷": "#e8b800", "無": "#8b8fa3" };
  const RARITY_COLOR = { R: "#3f7fbf", SR: "#8a4fbf", SSR: "#d99a00" };
  // レアリティ演出の終わりまで（ms）。カードの登場クラスを残す長さの目安
  const SUMMON_MS = { N: 1200, R: 1600, SR: 3000, SSR: 4400 };
  const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const BOLT_SVG = '<svg class="fx-bolt" aria-hidden="true" viewBox="0 0 70 120" preserveAspectRatio="none"><polyline points="42,0 22,38 40,44 14,84 32,90 18,120" stroke="#fff" stroke-width="7"/><polyline points="42,0 22,38 40,44 14,84 32,90 18,120" stroke="#e8b800" stroke-width="3"/></svg>';
  const CRACK_SVG = '<svg viewBox="0 0 100 140" preserveAspectRatio="none" aria-hidden="true"><polyline points="62,0 54,26 66,44 48,70 60,92 44,118 50,140"/><polyline points="48,70 28,78 16,100"/><polyline points="66,44 86,52 96,74"/><polyline points="60,92 78,104 84,126"/></svg>';
  const CROWN_SVG = '<svg viewBox="0 0 64 40" aria-hidden="true"><path d="M4 36 L8 8 L22 22 L32 4 L42 22 L56 8 L60 36 Z" fill="#e2b443" stroke="#8a5f0b" stroke-width="3" stroke-linejoin="round"/><circle cx="8" cy="8" r="3.5" fill="#fff3c4" stroke="#8a5f0b" stroke-width="1.5"/><circle cx="32" cy="4" r="3.5" fill="#fff3c4" stroke="#8a5f0b" stroke-width="1.5"/><circle cx="56" cy="8" r="3.5" fill="#fff3c4" stroke="#8a5f0b" stroke-width="1.5"/></svg>';
  const timers = new Set();
  const shown = { code: null, opp: null };

  const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const rand = (a, b) => a + Math.random() * (b - a);
  const layer = () => document.getElementById("fx-layer");
  const svgDataUri = (svg) => "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
  const elOf = (x) => (typeof x === "function" ? x() : x);

  // clear() で止まるタイマー
  function later(fn, ms) {
    const t = setTimeout(() => { timers.delete(t); fn(); }, ms);
    timers.add(t);
    return t;
  }
  // 演出用の要素（読み上げ対象外）。片付けは clear() が .fx-temp をまとめて消す
  function tempEl(parent, cls) {
    const el = document.createElement("div");
    el.className = "fx-temp " + cls;
    el.setAttribute("aria-hidden", "true");
    parent.append(el);
    return el;
  }
  // 親ごと消える子要素
  function child(parent, cls, vars) {
    const el = document.createElement("div");
    el.className = cls;
    Object.keys(vars || {}).forEach((k) => el.style.setProperty(k, vars[k]));
    parent.append(el);
    return el;
  }
  function classOn(el, name, ms) {
    if (!el) return;
    el.classList.add("fx-on-" + name);
    later(() => el.classList.remove("fx-on-" + name), ms);
  }
  function center(el) {
    const r = el.getBoundingClientRect();
    return { x: Math.min(Math.max(r.left + r.width / 2, 0), window.innerWidth), y: Math.min(Math.max(r.top + r.height / 2, 0), window.innerHeight) };
  }
  function coins(parent, count, loop) {
    for (let i = 0; i < count; i++) {
      const c = child(parent, "fx-coin", { "--dur": rand(1.6, 2.8).toFixed(2) + "s", "--delay": rand(0, loop ? 2.8 : 1.2).toFixed(2) + "s", "--dx": Math.round(rand(-40, 40)) + "px" });
      const size = Math.round(rand(12, 21));
      c.style.width = c.style.height = size + "px";
      c.style.left = rand(0, 100).toFixed(1) + "%";
      if (loop) c.style.animationIterationCount = "infinite";
    }
  }
  // 画面を切り替えるとき・再生を止めるときに、演出の要素・クラス・タイマーを残さない
  function clear() {
    timers.forEach((t) => clearTimeout(t));
    timers.clear();
    document.querySelectorAll(".fx-temp").forEach((el) => el.remove());
    const lay = layer();
    if (lay) lay.textContent = "";   // 金貨の雨など、レイヤーに直接置いた要素も消す
    document.querySelectorAll('[class*="fx-on-"]').forEach((el) => {
      Array.from(el.classList).forEach((c) => { if (c.startsWith("fx-on-")) el.classList.remove(c); });
    });
    shown.code = null; shown.opp = null;
    closePopup();
  }

  // ---------- ロビー・待機 ----------
  // ルームコードのドラム回転（コードが変わったときだけ回す）
  function roomCode(el, code) {
    el.setAttribute("aria-label", "ルームコード " + code);
    if (shown.code === code) return;
    shown.code = code;
    if (reducedMotion()) { el.textContent = code; return; }
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
  // コピーしたボタンの近くに、レシートの切れ端を出す
  function copied(button) {
    const box = button.parentElement;
    box.querySelectorAll(".fx-copied").forEach((x) => x.remove());
    const el = tempEl(box, "fx-copied");
    el.textContent = "コピーしました";
    later(() => el.remove(), 1700);
  }
  // 相手の席の行が「待機中」から変わった瞬間に一度だけ「参戦」（CPUなら「CPU」）ハンコ。now は "empty" | "joined" | "cpu"
  function seatState(li, now) {
    const prev = shown.opp;
    shown.opp = now;
    if (prev === "empty" && now !== "empty") seatJoined(li, { cpu: now === "cpu" });
  }
  function seatJoined(li, opt) {
    const cpu = !!(opt && opt.cpu);
    li.querySelectorAll(".fx-seat-stamp, .fx-seat-dust").forEach((x) => x.remove());
    const stamp = tempEl(li, "fx-stamp fx-seat-stamp" + (cpu ? " is-blue" : ""));
    stamp.textContent = cpu ? "CPU" : "参戦";
    if (reducedMotion()) return;
    classOn(li, "slide", 1000);
    [[-30, -30], [30, -30], [-30, 30], [30, 30]].forEach(([dx, dy]) => {
      const d = tempEl(li, "fx-seat-dust");
      d.style.setProperty("--dx", dx + "px"); d.style.setProperty("--dy", dy + "px");
      later(() => d.remove(), 1600);
    });
  }
  // 待機時間のリング。secondsLeft が null ならリングを隠す（止める）
  function waitRing(ring, secondsLeft, totalSeconds) {
    ring.classList.toggle("hidden", secondsLeft == null);
    if (secondsLeft == null) return;
    const left = Math.max(0, Number(secondsLeft));
    const ratio = Math.min(left / Math.max(Number(totalSeconds) || left, 1), 1);
    ring.querySelector(".fx-wait-ring-bar").style.strokeDashoffset = String(100 * (1 - ratio));
    ring.classList.toggle("is-low", ratio <= 0.15);
    ring.querySelector("b").textContent = String(left);
  }

  // ---------- カード召喚（ポップアップ） ----------
  // node は呼び出し側が作ったカードの要素。ポップアップの中で登場・レアリティの光・ラッキーボーナスを出す。
  // 手札欄のカードには演出をかけない。tileEl は選んだレシート（要素、または要素を返す関数）で、ちぎれる演出に使う
  let popup = null, popupKey = null;
  function closePopup() {
    if (popupKey) { document.removeEventListener("keydown", popupKey); popupKey = null; }
    if (popup) { popup.remove(); popup = null; }
    const lay = layer();
    if (lay) lay.textContent = "";   // ポップアップの光・金貨・スロットも一緒に消す
  }
  function summon(node, card, opt) {
    closePopup();
    const tile = opt && opt.tileEl;
    const pop = document.createElement("div");
    pop.className = "fx-popup";
    pop.setAttribute("role", "dialog");
    pop.setAttribute("aria-modal", "true");
    pop.setAttribute("aria-label", "モンスターカードが現れました");
    const stage = child(pop, "fx-popup-stage");
    const title = document.createElement("p");
    title.className = "fx-popup-title";
    title.textContent = "モンスターが現れた！";
    node.classList.add("is-revealed");
    const close = document.createElement("button");
    close.type = "button";
    close.className = "primary-button small-button";
    close.textContent = "閉じる";
    stage.append(title, node, close);
    close.addEventListener("click", closePopup);
    pop.addEventListener("click", (ev) => { if (ev.target === pop) closePopup(); });
    popupKey = (ev) => { if (ev.key === "Escape") closePopup(); };
    document.addEventListener("keydown", popupKey);
    layer().append(pop);
    popup = pop;
    close.focus({ preventScroll: true });
    const cardEl = () => (popup === pop ? pop.querySelector(".mcard") : null);   // 閉じたあとは null
    if (!reducedMotion()) {
      classOn(elOf(tile), "tear", 450);
      later(() => { const el = cardEl(); if (el) rarityLight(card.rarity, center(el)); }, 250);
    }
    if (card.lucky) later(() => { const el = cardEl(); if (el) lucky(el); }, 700);
  }

  function rarityLight(rarity, c) {
    const lay = layer();
    const color = RARITY_COLOR[rarity];
    if (!color) return;   // N は光なし
    const put = (cls, ms, vars) => {
      const el = tempEl(lay, cls);
      el.style.setProperty("--x", c.x + "px"); el.style.setProperty("--y", c.y + "px");
      Object.keys(vars || {}).forEach((k) => el.style.setProperty(k, vars[k]));
      later(() => el.remove(), ms);
      return el;
    };
    if (rarity === "SSR") put("fx-flash", 700);
    else put("fx-ring-burst", 1100, { "--c": color });
    if (rarity === "SR" || rarity === "SSR") {
      put("fx-curtain", 2500);
      put("fx-pillar", 2300, { "--c": color, "--w": rarity === "SSR" ? "340px" : "220px" });
    }
    if (rarity === "SSR") {
      const box = tempEl(lay, "fx-coins");
      coins(box, 28, false);
      later(() => box.remove(), 4400);
    }
  }
  // ラッキーボーナス：スロットが 7・7・7 でそろい、ハンコが押される
  function lucky(cardEl) {
    const r = cardEl.getBoundingClientRect();
    const g = tempEl(layer(), "fx-lucky");
    const side = window.innerWidth - r.right >= 170;
    g.style.setProperty("--x", (side ? r.right + 6 : r.left + r.width / 2) + "px");
    g.style.setProperty("--y", Math.min(Math.max(r.top + r.height / 2, 90), window.innerHeight - 90) + "px");
    g.style.setProperty("--tx", side ? "0" : "-50%");
    g.style.setProperty("--out", "4.6s");
    const slots = child(g, "fx-slots");
    [0.9, 1.25, 1.6].forEach((dur) => {
      const win = child(slots, "fx-slot");
      const strip = child(win, "fx-slot-strip", { "--dur": dur + "s" });
      for (let k = 0; k < 8; k++) {
        const s = document.createElement("span");
        s.textContent = k < 7 ? String(Math.floor(Math.random() * 10)) : "7";
        strip.append(s);
      }
    });
    const stamp = child(g, "fx-stamp fx-lucky-stamp");
    const t1 = document.createElement("span"); t1.textContent = "ラッキーボーナス";
    const t2 = document.createElement("small"); t2.textContent = "ATK・DEF ×1.10";
    stamp.append(t1, t2);
    later(() => g.remove(), reducedMotion() ? 3500 : 5400);
  }

  // ---------- 対戦 ----------
  // 登場。戻り値は登場が終わるまでのms（止める設定なら0）
  function battleIntro(arena) {
    if (reducedMotion()) return 0;
    const fighters = arena.querySelectorAll(".fighter");
    classOn(fighters[0], "in-left", 800);
    classOn(fighters[1], "in-right", 800);
    classOn(arena, "intro", 1400);
    const ring = tempEl(arena.querySelector(".vs"), "fx-vs-ring");
    later(() => ring.remove(), 1800);
    return 1200;
  }
  // 攻撃された側（defBox）に、ダメージ数字・攻撃側の属性のヒット演出・会心の一撃を出す
  function hit(defBox, attCard, e) {
    damage(defBox, e);
    if (reducedMotion()) return;
    const box = tempEl(defBox, "fx-hitfx");
    later(() => box.remove(), 1500);
    if (attCard.element === "炎") {
      [22, 50, 76].forEach((l, i) => { const f = child(box, "fx-flame", { "--d": (i * 0.12) + "s" }); f.style.left = l + "%"; });
    } else if (attCard.element === "風") {
      [0, 1, 2].forEach((i) => child(box, "fx-wind", { "--d": (i * 0.12) + "s" }));
    } else if (attCard.element === "土") {
      [18, 38, 58, 76].forEach((l, i) => {
        const s = Math.round(rand(22, 32));
        const rk = child(box, "fx-rock", { "--s": s + "px", "--d": (i * 0.07).toFixed(2) + "s", "--ty": Math.round(rand(170, 230)) + "px", "--r": Math.round(rand(120, 300)) + "deg" });
        rk.style.left = l + "%";
      });
      child(box, "fx-sand");
    } else if (attCard.element === "雷") {
      box.insertAdjacentHTML("beforeend", BOLT_SVG);   // 固定のSVGだけ。APIの文字列は入れない
      const flash = tempEl(layer(), "fx-screen-flash");
      later(() => flash.remove(), 600);
    }
    if (e.crit) {
      const arena = defBox.closest(".arena");
      if (arena) {
        const w = tempEl(arena, "fx-arena-flash");
        later(() => w.remove(), 400);
        classOn(arena, "hitstop", 350);
      }
      child(box, "fx-slash");
    }
  }
  // ダメージ数字（ラベル付き）
  function damage(defBox, e) {
    const f = tempEl(defBox, "float-dmg" + (e.crit ? " is-crit" : e.elem < 1 ? " is-weak" : ""));
    const label = e.crit ? "会心！" : e.skill ? "必殺技" : e.elem > 1 ? "属性有利" : e.elem < 1 ? "いまひとつ" : "";
    if (label) { const l = document.createElement("span"); l.className = "fx-dmg-label"; l.textContent = label; f.append(l); }
    const n = document.createElement("b");
    n.textContent = e.damage;
    f.append(n);
    later(() => f.remove(), 1300);
  }
  // 必殺技のカットイン（文字はすべて textContent）
  function cutIn(arena, card, e, ms) {
    const dim = tempEl(arena, "fx-dim");
    const band = tempEl(arena, "fx-cutin");
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
    later(() => { dim.remove(); band.remove(); }, ms + 50);
  }
  // KO：ひびと「KO」ハンコ
  function ko(defBox) {
    const card = defBox.querySelector(".mcard");
    if (!card) return;
    const crack = tempEl(defBox, "fx-crack");
    crack.innerHTML = CRACK_SVG;   // 固定のSVGだけ
    crack.style.left = card.offsetLeft + "px"; crack.style.top = card.offsetTop + "px";
    crack.style.width = card.offsetWidth + "px"; crack.style.height = card.offsetHeight + "px";
    const stamp = tempEl(defBox, "fx-stamp fx-ko");
    stamp.textContent = "KO";
    stamp.style.left = (card.offsetLeft + card.offsetWidth / 2) + "px";
    stamp.style.top = (card.offsetTop + card.offsetHeight * 0.45) + "px";
  }

  // ---------- 結果 ----------
  // ログから自分の視点の数値を集める。slip.mine(e) は「自分の行動か」を返す
  function summarize(log, mine) {
    const s = { acts: 0, dealt: 0, taken: 0, crits: 0, skills: 0 };
    log.forEach((e) => {
      if (mine(e)) { s.acts++; s.dealt += e.damage; if (e.crit) s.crits++; if (e.skill) s.skills++; } else s.taken += e.damage;
    });
    return s;
  }
  // 見出し・王冠・金貨の雨・バトル明細。opt = { outcome: "WIN"|"LOSE"|"DRAW", letters, slip: { log, reason, mine } }
  function result(view, opt) {
    const win = opt.outcome === "WIN", draw = opt.outcome === "DRAW";
    const banner = view.querySelector("#result-banner");
    banner.textContent = "";
    banner.classList.add("is-split");
    banner.setAttribute("role", "img");
    banner.setAttribute("aria-label", opt.letters);
    Array.from(opt.letters).forEach((ch, i) => {
      const s = document.createElement("span");
      s.className = "fx-ch";
      s.setAttribute("aria-hidden", "true");
      s.style.setProperty("--i", i);
      s.textContent = ch;
      banner.append(s);
    });
    if (!draw) {
      const winner = view.querySelector("#result-arena .fighter.is-winner");
      if (winner) tempEl(winner, "fx-crown").innerHTML = CROWN_SVG;   // 固定のSVGだけ
    }
    if (win && !reducedMotion()) coins(layer(), 30, true);
    if (opt.slip) slip(view.querySelector("#result-slip"), opt.outcome, opt.slip);
  }
  function slip(box, outcome, data) {
    box.textContent = "";
    const s = summarize(data.log, data.mine);
    const rows = [["行動回数", s.acts + "回"], ["与えたダメージ", String(s.dealt)], ["受けたダメージ", String(s.taken)], ["会心の回数", s.crits + "回"], ["必殺技の回数", s.skills + "回"], ["決着", data.reason === "KO" ? "KO" : "20ラウンド終了（判定）"]];
    const title = document.createElement("p");
    title.className = "fx-slip-title";
    title.textContent = "バトル明細";
    box.append(title);
    rows.forEach(([k, v], i) => {
      const row = document.createElement("div");
      row.className = "fx-slip-row" + (i === rows.length - 1 ? " is-total" : "");
      row.style.setProperty("--i", i);
      const key = document.createElement("span"); key.textContent = k;
      const val = document.createElement("b"); val.textContent = v;
      row.append(key, val);
      box.append(row);
    });
    const stamp = document.createElement("div");
    stamp.className = "fx-stamp fx-slip-stamp" + (outcome === "DRAW" ? " is-draw" : outcome === "WIN" ? "" : " is-lose");
    stamp.setAttribute("aria-hidden", "true");
    stamp.textContent = outcome === "DRAW" ? "分" : outcome === "WIN" ? "勝" : "負";
    box.append(stamp);
  }

  window.MonsterFx = { reducedMotion, later, clear, roomCode, copied, seatState, seatJoined, waitRing, summon, closePopup, battleIntro, hit, damage, cutIn, ko, result, summarize };
})();
