// モンスターレシート対戦ゲーム デモ用エンジン
// doc/34_monster_receipt_battle_requirements.html の5章（カード生成）と6章（対戦）を再現する。
// DOM・DB・Gemini に依存しない純粋な関数だけで構成する（本番でも同じ分け方にする）。
(function (root) {
  "use strict";

  const ALGORITHM_VERSION = 2;
  const RARITY_R = 275, RARITY_SR = 315, RARITY_SSR = 345;   // 乱数の試算（4000件）で N約25%・R約40%・SR約25%・SSR約10%
  const MAX_ROUNDS = 20;

  const ELEMENT_BY_CATEGORY = { "飲食": "炎", "食料品": "土", "日用品": "風", "交通・移動": "雷" };
  const ADVANTAGE = { "炎": "風", "風": "土", "土": "雷", "雷": "炎" };
  const SKILLS = {
    "炎": ["業火の咆哮", "紅蓮撃", "火炎旋風"],
    "土": ["大地割り", "岩石砲", "地鳴り"],
    "風": ["烈風刃", "大竜巻", "疾風突き"],
    "雷": ["雷撃", "天の裁き", "紫電一閃"],
    "無": ["体当たり", "虚空撃", "無双乱舞"]
  };
  const NAME_PREFIX = {
    "炎": ["ブレイ", "イグニ", "カグツ", "フレア"],
    "土": ["ガイア", "ロック", "ドワ", "グラン"],
    "風": ["シルフ", "ゼピュ", "ウィン", "ハヤ"],
    "雷": ["ボルト", "ライ", "テンペ", "スパー"],
    "無": ["ノア", "ヌル", "ゼロ", "ボイド"]
  };
  const NAME_SUFFIX = ["ン", "ドラ", "モン", "ゴン", "ラス", "ビー"];
  const PALETTE = {
    "炎": ["#e4572e", "#ffc065", "#7a1f0c"],
    "土": ["#a7803f", "#e2c98a", "#4f3a14"],
    "風": ["#3fb596", "#c5f3e4", "#17594a"],
    "雷": ["#e8b800", "#8f7bff", "#40358f"],
    "無": ["#8b8fa3", "#e2e4ee", "#454859"]
  };

  // ---- 乱数 ----
  function mulberry32(seed) {
    let a = seed >>> 0;
    return function () {
      a = (a + 0x6D2B79F5) >>> 0;
      let t = a;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  function seedFromSha(sha) { return parseInt(sha.slice(0, 8), 16) >>> 0; }

  // デモ用：文字列から64桁の16進数を作る（本番は画像のSHA-256を使う）
  function fakeSha(text) {
    let out = "";
    for (let round = 0; out.length < 64; round++) {
      let h = (0x811C9DC5 ^ Math.imul(round + 1, 0x9E3779B1)) >>> 0;
      for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 0x01000193) >>> 0;
      h = Math.imul(h ^ (h >>> 16), 0x85EBCA6B) >>> 0;
      h = Math.imul(h ^ (h >>> 13), 0xC2B2AE35) >>> 0;
      out += ((h ^ (h >>> 16)) >>> 0).toString(16).padStart(8, "0");
    }
    return out.slice(0, 64);
  }

  // ---- カード生成（5章）----
  function determineElement(items) {
    const sums = new Map();
    for (const it of items) sums.set(it.category, (sums.get(it.category) || 0) + (it.amount || 0));
    let best = null, bestSum = -1;
    for (const [category, sum] of sums) if (sum > bestSum) { best = category; bestSum = sum; }
    return best === null ? "無" : (ELEMENT_BY_CATEGORY[best] || "無");
  }

  function isZoroMe(total) {
    return (total >= 111 && /^(\d)\1+$/.test(String(total))) || total % 1000 === 777;
  }

  // ラッキーボーナス：①合計金額がゾロ目／末尾777 ②購入時刻の時と分が同じ（11:11など） ③日付が7のつく日
  // 金額だけが分かる印にならないよう、3つの条件のどれかで付く。
  function isLucky(total, purchasedAt) {
    const hh = purchasedAt ? purchasedAt.slice(11, 13) : "";
    const mm = purchasedAt ? purchasedAt.slice(14, 16) : "";
    const day = purchasedAt ? parseInt(purchasedAt.slice(8, 10), 10) : 0;
    return isZoroMe(total) || (hh !== "" && hh === mm) || day % 10 === 7;
  }

  function rarityOf(power) {
    if (power >= RARITY_SSR) return "SSR";
    if (power >= RARITY_SR) return "SR";
    if (power >= RARITY_R) return "R";
    return "N";
  }

  // 日付要素：日(0〜1)×0.7 ＋ 週末なら0.3
  function dateFactor(purchasedAt) {
    if (!purchasedAt) return 0.5;
    const y = parseInt(purchasedAt.slice(0, 4), 10), m = parseInt(purchasedAt.slice(5, 7), 10), d = parseInt(purchasedAt.slice(8, 10), 10);
    const weekday = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
    return ((d - 1) / 30) * 0.7 + (weekday === 0 || weekday === 6 ? 0.3 : 0);
  }

  function generateCard(receipt, sha, source) {
    const seed = seedFromSha(sha);
    const items = (receipt.items || []).filter((it) => it.name && it.name.trim());
    const itemSum = items.reduce((s, it) => s + (it.amount || 0), 0);
    const total = receipt.totalAmount != null ? receipt.totalAmount : itemSum;
    const maxAmount = items.reduce((m, it) => Math.max(m, it.amount || 0), 0);
    const hour = receipt.purchasedAt ? parseInt(receipt.purchasedAt.slice(11, 13), 10) : 12;
    const chars = receipt.charCount != null ? receipt.charCount : 0;
    const element = determineElement(items);

    // 要素（すべて0〜1）。金額の影響は、どのステータスでも25%以下に抑える。
    const f = {
      A: Math.min(total, 30000) / 30000,
      M: Math.min(maxAmount, 10000) / 10000,
      C: Math.min(chars, 600) / 600,
      N: Math.min(items.length, 20) / 20,
      T: Math.min(12, Math.abs(hour - 12)) / 12,
      W: dateFactor(receipt.purchasedAt)
    };
    const r = (k) => ((seed >>> (k * 5)) & 255) / 255;   // 画像ハッシュ由来のゆらぎ（0〜1）
    const base = {
      hp: 200 + Math.floor(500 * (0.25 * f.A + 0.30 * f.C + 0.15 * f.N + 0.15 * f.W + 0.15 * r(0))),
      atk: 40 + Math.floor(100 * (0.25 * f.M + 0.25 * f.C + 0.15 * f.T + 0.15 * f.W + 0.20 * r(1))),
      def: 30 + Math.floor(80 * (0.15 * f.A + 0.30 * f.N + 0.20 * f.C + 0.15 * f.W + 0.20 * r(2))),
      spd: 40 + Math.floor(60 * (0.35 * f.T + 0.20 * f.W + 0.15 * f.C + 0.10 * f.N + 0.20 * r(3))),
      luck: 5 + Math.floor(20 * (0.50 * r(4) + 0.25 * f.W + 0.25 * f.C))
    };
    const stats = { ...base };
    const mods = [];
    const up10 = (v) => Math.floor((v * 11) / 10);
    switch (receipt.storeCategory) {
      case "スーパー": stats.hp = up10(stats.hp); mods.push("店舗補正 HP×1.10"); break;
      case "コンビニ": stats.spd = up10(stats.spd); mods.push("店舗補正 SPD×1.10"); break;
      case "ドラッグストア": stats.def = up10(stats.def); mods.push("店舗補正 DEF×1.10"); break;
      case "飲食店": stats.atk = up10(stats.atk); mods.push("店舗補正 ATK×1.10"); break;
      default: stats.luck += 3; mods.push("店舗補正 LUCK+3");
    }
    const lucky = isLucky(total, receipt.purchasedAt);
    if (lucky) {
      stats.atk = up10(stats.atk); stats.def = up10(stats.def); stats.luck += 10;
      mods.push("ラッキーボーナス ATK・DEF×1.10 / LUCK+10");
    }
    const power = Math.floor(stats.hp / 5 + stats.atk + stats.def + stats.spd / 2 + stats.luck * 2);
    const skillPower = Math.round((1.5 + 0.5 * (0.2 * f.M + 0.4 * f.C + 0.4 * r(5))) * 100) / 100;
    const skillName = SKILLS[element][(seed >>> 8) % 3];
    const name = NAME_PREFIX[element][(seed >>> 12) % 4] + NAME_SUFFIX[(seed >>> 16) % 6];

    const card = {
      id: null,
      sha,
      source: source || "ANALYZE",
      name,
      element,
      rarity: rarityOf(power),
      ...stats,
      power,
      skillName,
      skillPower,
      lucky,
      flavor: `${receipt.storeCategory}のレシートから生まれた${element}の魔物。${items.length}品の力を宿している。`,
      storeName: receipt.storeName,
      storeCategory: receipt.storeCategory,
      totalAmount: total,
      purchasedAt: receipt.purchasedAt,
      illustrationSource: "FALLBACK",
      algorithmVersion: ALGORITHM_VERSION,
      receipt,
      basis: { base, mods, factors: f, itemCount: items.length, seed }
    };
    card.svg = fallbackSvg(card, seed);
    return card;
  }

  // ---- 代替SVG（5.7）：検証済みのGemini SVGが使えないときの描画 ----
  function fallbackSvg(card, seed) {
    const rng = mulberry32(seed ^ 0xA5A5A5A5);
    const [main, light, dark] = PALETTE[card.element];
    const hueShift = Math.floor(rng() * 24) - 12;
    const gid = "g" + (seed >>> 0).toString(16);
    const eyes = 1 + Math.floor(rng() * 3);
    const horns = Math.floor(rng() * 4);
    const fangs = rng() < 0.5;
    const cheeks = rng() < 0.6;
    const body = {
      "炎": `<path d="M100 26C132 68 170 98 162 140C156 172 128 186 100 186C72 186 44 172 38 140C30 98 68 68 100 26Z"/>`,
      "土": `<path d="M44 80C44 56 66 44 100 44C134 44 156 56 156 80L166 140C168 170 140 184 100 184C60 184 32 170 34 140Z"/>`,
      "風": `<g><circle cx="70" cy="120" r="42"/><circle cx="118" cy="104" r="50"/><circle cx="138" cy="138" r="38"/><rect x="60" y="120" width="90" height="56" rx="26"/></g>`,
      "雷": `<polygon points="100,22 162,68 176,140 132,184 68,184 24,140 38,68"/>`,
      "無": `<circle cx="100" cy="112" r="74"/>`
    }[card.element];

    const eyeY = 108;
    const eyeXs = eyes === 1 ? [100] : eyes === 2 ? [76, 124] : [62, 100, 138];
    const eyeMarkup = eyeXs.map((x) =>
      `<circle cx="${x}" cy="${eyeY}" r="15" fill="#fff"/><circle cx="${x + 2}" cy="${eyeY + 2}" r="8" fill="#241a10"/><circle cx="${x + 5}" cy="${eyeY - 2}" r="3" fill="#fff"/>`
    ).join("");
    const hornMarkup = Array.from({ length: horns }, (_, i) => {
      const x = horns === 1 ? 100 : 56 + (88 / (horns - 1)) * i;
      return `<polygon points="${x - 9},58 ${x},24 ${x + 9},58" fill="${dark}"/>`;
    }).join("");
    const mouth = fangs
      ? `<path d="M72 146Q100 168 128 146Q100 156 72 146Z" fill="${dark}"/><polygon points="82,150 88,164 94,153" fill="#fff"/><polygon points="106,153 112,164 118,150" fill="#fff"/>`
      : `<path d="M78 148Q100 166 122 148" fill="none" stroke="${dark}" stroke-width="5" stroke-linecap="round"/>`;
    const cheekMarkup = cheeks
      ? `<circle cx="56" cy="136" r="9" fill="#ff8aa0" opacity=".55"/><circle cx="144" cy="136" r="9" fill="#ff8aa0" opacity=".55"/>`
      : "";
    const extra = {
      "炎": `<path d="M100 8C112 26 124 30 116 48C108 40 104 40 100 28C96 40 92 40 84 48C76 30 90 26 100 8Z" fill="${light}"/>`,
      "土": `<circle cx="64" cy="76" r="6" fill="${dark}" opacity=".35"/><circle cx="138" cy="170" r="7" fill="${dark}" opacity=".35"/><circle cx="48" cy="150" r="5" fill="${dark}" opacity=".35"/>`,
      "風": `<path d="M30 60q16-14 32 0M150 50q16-14 32 0" fill="none" stroke="${light}" stroke-width="5" stroke-linecap="round"/>`,
      "雷": `<polygon points="108,162 90,184 102,184 94,200 118,176 104,176" fill="${light}"/>`,
      "無": `<circle cx="100" cy="112" r="74" fill="none" stroke="${light}" stroke-width="3" stroke-dasharray="4 8"/>`
    }[card.element];
    const feet = `<ellipse cx="72" cy="188" rx="20" ry="9" fill="${dark}"/><ellipse cx="128" cy="188" rx="20" ry="9" fill="${dark}"/>`;
    const sparkle = card.rarity === "SSR" || card.rarity === "SR"
      ? `<path d="M168 30l4 10 10 4-10 4-4 10-4-10-10-4 10-4z" fill="#fff6c9"/><path d="M26 40l3 8 8 3-8 3-3 8-3-8-8-3 8-3z" fill="#fff6c9"/>`
      : "";

    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200">` +
      `<defs><radialGradient id="${gid}" cx="40%" cy="30%" r="80%"><stop offset="0" stop-color="${light}"/><stop offset=".55" stop-color="${main}"/><stop offset="1" stop-color="${dark}"/></radialGradient></defs>` +
      `<g fill="url(#${gid})" stroke="${dark}" stroke-width="3" stroke-linejoin="round" transform="rotate(${hueShift} 100 110)">` +
      `${feet}${hornMarkup}${body}</g>${extra}${eyeMarkup}${cheekMarkup}${mouth}${sparkle}</svg>`;
  }

  function svgDataUri(svg) { return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg); }

  // ---- 対戦（6章）----
  function elementMultiplier(attacker, defender) {
    if (ADVANTAGE[attacker] === defender) return 1.25;
    if (ADVANTAGE[defender] === attacker) return 0.8;
    return 1;
  }

  function battle(cardA, cardB, battleSeed) {
    const rng = mulberry32(battleSeed);
    const sides = [cardA, cardB];
    const hp = [cardA.hp, cardB.hp];
    const actions = [0, 0];
    const first = cardA.spd > cardB.spd ? 0 : cardB.spd > cardA.spd ? 1 : ((battleSeed & 1) === 0 ? 0 : 1);
    const log = [];
    let winner = null, reason = null;

    outer:
    for (let round = 1; round <= MAX_ROUNDS; round++) {
      for (const k of [first, 1 - first]) {
        const o = 1 - k;
        const att = sides[k], def = sides[o];
        actions[k]++;
        const skill = actions[k] % 3 === 0;
        const mult = skill ? att.skillPower : 1;
        const baseDamage = Math.max(1, att.atk * mult - def.def * 0.5);
        const variance = 0.9 + 0.2 * rng();
        const elem = elementMultiplier(att.element, def.element);
        const crit = rng() < att.luck / 100;
        const damage = Math.max(1, Math.round(baseDamage * variance * elem * (crit ? 1.5 : 1)));
        hp[o] = Math.max(0, hp[o] - damage);
        log.push({ round, actor: k, target: o, skill, skillName: skill ? att.skillName : "通常攻撃", damage, crit, elem, hpAfter: hp[o] });
        if (hp[o] === 0) { winner = k; reason = "KO"; break outer; }
      }
    }
    if (winner === null) {
      reason = "JUDGE";
      const ratio = [hp[0] / cardA.hp, hp[1] / cardB.hp];
      if (ratio[0] !== ratio[1]) winner = ratio[0] > ratio[1] ? 0 : 1;
      else if (cardA.power !== cardB.power) winner = cardA.power > cardB.power ? 0 : 1;
      else winner = "DRAW";
    }
    return { battleSeed, first, log, winner, reason, finalHp: hp };
  }

  const api = { ALGORITHM_VERSION, MAX_ROUNDS, SKILLS, PALETTE, mulberry32, seedFromSha, fakeSha, determineElement, isZoroMe, isLucky, generateCard, fallbackSvg, svgDataUri, elementMultiplier, battle };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.MonsterEngine = api;
})(typeof window !== "undefined" ? window : globalThis);
