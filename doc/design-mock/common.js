/* 画面切替・数値カウントアップ・カウントダウン・選択トグル。ライブラリ不使用 */
(() => {
  const rm = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const sprite = `<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs>
  <symbol id="i-home" viewBox="0 0 24 24"><path d="M3 11l9-8 9 8M5 10v10h14V10M10 20v-6h4v6"/></symbol>
  <symbol id="i-battle" viewBox="0 0 24 24"><path d="M4 4l10 10M20 4L10 14M12 16l-4 4M12 16l4 4"/></symbol>
  <symbol id="i-receipt" viewBox="0 0 24 24"><path d="M6 3h12v18l-3-2-3 2-3-2-3 2zM9 8h6M9 12h6"/></symbol>
  <symbol id="i-card" viewBox="0 0 24 24"><path d="M6 3h12a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V5a2 2 0 012-2zM8 16l3-4 2 3 2-2 2 3"/></symbol>
  <symbol id="i-timer" viewBox="0 0 24 24"><circle cx="12" cy="13" r="8"/><path d="M12 8v5l3 2M9 2h6"/></symbol>
  <symbol id="i-check" viewBox="0 0 24 24"><path d="M5 12l5 5 9-10"/></symbol>
  <symbol id="i-search" viewBox="0 0 24 24"><circle cx="11" cy="11" r="6"/><path d="M16 16l5 5"/></symbol>
  <symbol id="i-plus" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></symbol>
  </defs></svg>`;
  document.body.insertAdjacentHTML('afterbegin', sprite);

  const fmt = n => n.toLocaleString('ja-JP');
  const screens = [...document.querySelectorAll('.screen')];
  const navs = [...document.querySelectorAll('[data-go]')];

  function count(root) {
    root.querySelectorAll('[data-to]').forEach(el => {
      const to = +el.dataset.to, pre = el.dataset.pre || '', suf = el.dataset.suf || '';
      if (rm) { el.textContent = pre + fmt(to) + suf; return; }
      const t0 = performance.now(), d = 900;
      const step = t => {
        const p = Math.min((t - t0) / d, 1), e = 1 - Math.pow(1 - p, 3);
        el.textContent = pre + fmt(Math.round(to * e)) + suf;
        if (p < 1) requestAnimationFrame(step);
      };
      requestAnimationFrame(step);
    });
  }

  function go(name) {
    if (!screens.some(s => s.dataset.screen === name)) name = 'home';
    screens.forEach(s => {
      const on = s.dataset.screen === name;
      s.hidden = !on;
      s.classList.toggle('is-on', on);
      if (on) count(s);
    });
    navs.forEach(n => n.dataset.go === name ? n.setAttribute('aria-current', 'page') : n.removeAttribute('aria-current'));
    scrollTo(0, 0);
  }
  navs.forEach(n => n.addEventListener('click', e => { e.preventDefault(); history.replaceState(null, '', '#' + n.dataset.go); go(n.dataset.go); }));

  /* [data-group] は同じ親の中でラジオ、[data-pick] はトグル */
  document.addEventListener('click', e => {
    const g = e.target.closest('[data-group]');
    if (g) {
      g.parentElement.querySelectorAll(`[data-group="${g.dataset.group}"]`).forEach(x => x.setAttribute('aria-pressed', x === g));
      return;
    }
    const p = e.target.closest('[data-pick]');
    if (p) p.setAttribute('aria-pressed', p.getAttribute('aria-pressed') !== 'true');
  });

  /* 待機カウントダウン（reduced-motion では静止） */
  document.querySelectorAll('[data-countdown]').forEach(el => {
    let s = +el.dataset.countdown;
    const show = () => el.textContent = String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
    show();
    if (!rm) setInterval(() => { s = s > 0 ? s - 1 : +el.dataset.countdown; show(); }, 1000);
  });

  go(location.hash.slice(1) || 'home');
})();
