// 日替わりテーマ。毎日 3:00（日本時間）に10種類が順番に切り替わる。
// 日付だけで決まるので、どの画面・どの端末でも同じ日は同じテーマになる。
// 確認用：URLに ?theme=1〜10 を付けるとそのテーマで表示する（保存はしない）。
(() => {
  const THEMES = [
    { id: "t01", name: "葡萄サロン", fonts: "Shippori+Mincho:wght@500;800&family=Cormorant+Garamond:ital,wght@1,600" },
    { id: "t02", name: "トッピング注文", fonts: "Dela+Gothic+One&family=Noto+Sans+JP:wght@500;900" },
    { id: "t03", name: "白磁と洋紅", fonts: "Zen+Kaku+Gothic+New:wght@300;500;700" },
    { id: "t04", name: "トラットリア図鑑", fonts: "Mochiy+Pop+One&family=M+PLUS+Rounded+1c:wght@500;800" },
    { id: "t05", name: "純喫茶ナイト", fonts: "Shippori+Mincho+B1:wght@500;800&family=Kiwi+Maru:wght@500" },
    { id: "t06", name: "黄昏ゾーン", fonts: "Zen+Maru+Gothic:wght@500;900&family=Cormorant+Garamond:ital,wght@1,600" },
    { id: "t07", name: "モノクロ索引", fonts: "Space+Mono:wght@400;700&family=Zen+Kaku+Gothic+New:wght@500;900" },
    { id: "t08", name: "食券機", fonts: "DotGothic16&family=Zen+Kaku+Gothic+New:wght@500;900" },
    { id: "t09", name: "泡のパフェ", fonts: "Zen+Maru+Gothic:wght@500;700;900" },
    { id: "t10", name: "レシート路線図", fonts: "BIZ+UDPGothic:wght@400;700" }
  ];
  const DAY = 86400000;
  const JST = 9 * 3600000;
  const SWITCH_HOUR = 3;

  // 日本時間の3:00を日の境目として、日番号を数える
  const dayNumber = (now) => Math.floor((now + JST - SWITCH_HOUR * 3600000) / DAY);
  const themeIndex = (now) => ((dayNumber(now) % THEMES.length) + THEMES.length) % THEMES.length;
  const nextSwitchAt = (now) => (dayNumber(now) + 1) * DAY - JST + SWITCH_HOUR * 3600000;
  window.ReceiptTheme = { THEMES, themeIndex, nextSwitchAt };

  const script = document.currentScript;
  const base = script ? script.src.replace(/[^/]*$/, "") : "/theme/";
  const root = document.documentElement;

  function link(key, href) {
    let el = document.head.querySelector(`link[data-theme-${key}]`);
    if (!el) {
      el = document.createElement("link");
      el.rel = "stylesheet";
      el.setAttribute(`data-theme-${key}`, "");
      document.head.appendChild(el);
    }
    if (el.getAttribute("href") !== href) el.setAttribute("href", href);
  }

  function pick() {
    const forced = Number(new URLSearchParams(location.search).get("theme"));
    return Number.isInteger(forced) && forced >= 1 && forced <= THEMES.length ? forced - 1 : themeIndex(Date.now());
  }

  function label(index) {
    const t = THEMES[index];
    document.querySelectorAll(".footer-note").forEach((note) => {
      let small = note.querySelector("[data-theme-label]");
      if (!small) {
        small = document.createElement("small");
        small.setAttribute("data-theme-label", "");
        small.style.cssText = "display:block;margin-top:6px;letter-spacing:.08em;font-style:normal";
        note.appendChild(small);
      }
      small.textContent = `本日のデザイン：案${String(index + 1).padStart(2, "0")} ${t.name}（毎日3時に切替）`;
    });
  }

  function apply() {
    const index = pick();
    const t = THEMES[index];
    root.setAttribute("data-theme", t.id);
    link("fonts", `https://fonts.googleapis.com/css2?family=${t.fonts}&display=swap`);
    link("base", `${base}base.css`);
    link("css", `${base}${t.id}.css`);
    label(index);
  }

  function schedule() {
    setTimeout(() => { apply(); schedule(); }, Math.max(1000, nextSwitchAt(Date.now()) - Date.now() + 500));
  }

  apply();
  document.addEventListener("DOMContentLoaded", () => label(pick()));
  schedule();
})();
