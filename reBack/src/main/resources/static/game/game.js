// モンスターレシート対戦の画面制御。
// ルーム・配布・勝敗はサーバー（/api/monster/rooms）が管理する。画面は状態を問い合わせて表示するだけ。
// 席トークンは sessionStorage に置き、ページを再読み込みしても同じ席に戻れるようにする。
(function () {
  "use strict";
  const API = "/api/monster/rooms";
  const $ = (id) => document.getElementById(id);
  const SPEEDS = [["ふつう", 1100], ["はやい", 450], ["ゆっくり", 1900]];
  const SESSION_KEY = "monster-seat";
  const HAND_LIMIT = 3;

  const state = {
    seat: null, code: null, token: null, view: "lobby",
    room: null, field: null, myId: null, sub: "pool",
    pollTimer: null, replay: null, speedIndex: 0, battle: null, busy: false, revealedId: null
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
    $("room-code").textContent = room.code;
    const mine = room.seat === "P1" ? "seat-p1" : "seat-p2", theirs = room.seat === "P1" ? "seat-p2" : "seat-p1";
    setSeatRow(mine, "あなた", "参加済み", false);
    if (room.mode === "CPU") setSeatRow(theirs, "コンピュータ（自動）", "CPU", false);
    else if (room.opponentJoined) setSeatRow(theirs, "相手プレイヤー", "参加済み", false);
    else setSeatRow(theirs, "参加を待っています…", "待機中", true);
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
    const revealedId = state.revealedId;
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
      $("gen-preview").innerHTML = `<div class="card-grid">${cardHtml(f.imageCard, { clickable: !f.confirmed, selected: f.imageCard.id === state.myId })}</div>`;
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

  function flipReceipt(index) {
    return withBusy(async () => {
      const res = await api("/" + state.code + "/flip", { method: "POST", body: { index } });
      state.field = res.field;
      state.revealedId = res.card.id;
      setTimeout(() => { state.revealedId = null; }, 700);
      setStatus($("pool-status"), `${res.card.name}が現れました！`);
    }, $("pool-status"));
  }

  async function onImagePicked(file) {
    if (!file) return;
    if (!/^image\/(jpeg|png)$/.test(file.type)) { setStatus($("gen-status"), "JPEGまたはPNGの画像を選んでください。", true); return; }
    if (file.size > 5 * 1024 * 1024) { setStatus($("gen-status"), "画像は5 MiB以下にしてください。", true); return; }
    if (state.field && (state.field.confirmed || handFull())) { setStatus($("gen-status"), "今は画像からカードを作れません。", true); return; }
    await withBusy(async () => {
      setStatus($("gen-status"), "レシートを読み取って、カードを作っています…（20秒ほどかかることがあります）");
      const form = new FormData();
      form.append("file", file);
      const res = await api("/" + state.code + "/image-card", { method: "POST", body: form });
      state.field = res.field;
      state.myId = res.card.id;
      setStatus($("gen-status"), res.message);
    }, $("gen-status"));
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
    return `<div class="hp-box"><div class="hp-label"><span>${esc(label)}</span><span class="hp-num">${card.hp} / ${card.hp}</span></div><div class="hp-bar"><i></i></div></div>${cardHtml(card)}`;
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
    $("fighter-0").style.setProperty("--lunge", "24px");
    $("fighter-1").style.setProperty("--lunge", "-24px");
    $("battle-log").innerHTML = "";
    const first = slotOf(battle.first);
    appendLog(`先攻：${slotCards[first].name}（素早さ ${slotCards[first].spd}）`);
    state.replay.timer = setInterval(playStep, SPEEDS[state.speedIndex][1]);
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
    const bar = box.querySelector(".hp-bar > i");
    const ratio = hp / max;
    bar.style.width = (ratio * 100) + "%";
    bar.className = ratio <= 0.25 ? "is-low" : ratio <= 0.5 ? "is-mid" : "";
  }

  function playStep(instant) {
    const r = state.replay;
    if (!r) return;
    const log = r.battle.log;
    if (r.step >= log.length) { finishBattle(); return; }
    const e = log[r.step++];
    const att = r.slotOf(e.actor), def = r.slotOf(e.target);
    setHp(def, e.hpAfter, r.slotCards[def].hp);
    const attBox = $("fighter-" + att), defBox = $("fighter-" + def);
    if (instant !== true) {
      attBox.classList.remove("is-attacking"); defBox.classList.remove("is-hit");
      void attBox.offsetWidth;
      attBox.classList.add("is-attacking"); defBox.classList.add("is-hit");
      const f = document.createElement("div");
      f.className = "float-dmg" + (e.crit ? " is-crit" : e.elem < 1 ? " is-weak" : "");
      f.textContent = e.damage;
      defBox.append(f);
      setTimeout(() => f.remove(), 1000);
    }
    const tags = [];
    if (e.skill) tags.push("必殺技");
    if (e.elem > 1) tags.push("属性有利");
    if (e.elem < 1) tags.push("属性不利");
    if (e.crit) tags.push("会心！");
    appendLog(`の${e.skillName}！ ${e.damage}ダメージ`, att, r.slotCards[att].name, tags);
    if (e.hpAfter === 0) defBox.classList.add("is-defeated");
    if (r.step >= log.length) { clearInterval(r.timer); if (instant !== true) setTimeout(finishBattle, 1300); }
  }

  function skipBattle() {
    const r = state.replay;
    if (!r) return;
    clearInterval(r.timer);
    while (r.step < r.battle.log.length) playStep(true);
    finishBattle();
  }

  function stopReplay() {
    if (state.replay) clearInterval(state.replay.timer);
    state.replay = null;
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
    banner.textContent = draw ? "DRAW" : iWon ? "YOU WIN!" : "YOU LOSE…";
    banner.classList.toggle("is-lose", !iWon);
    const winnerName = draw ? "" : b.cards[b.winner].name;
    $("result-detail").textContent = (cpu ? "【コンピュータ対戦】" : "") + (draw ? "引き分けです。" : `${winnerName}の勝利！`) +
      (b.reason === "KO" ? `（KO決着・${b.log.length}回の行動）` : "（20ラウンド終了：残りHP割合で判定）") +
      ` battleSeed：${b.battleSeed}`;
    setStatus($("result-status"), "");
    const mineCls = draw ? "" : iWon ? "is-winner" : "is-defeated";
    const oppCls = draw ? "" : iWon ? "is-defeated" : "is-winner";
    $("result-arena").innerHTML = `<div class="fighter ${mineCls}">${cardHtml(b.cards[me])}</div><div class="vs" aria-hidden="true">VS</div><div class="fighter ${oppCls}">${cardHtml(b.cards[opp])}</div>`;
    $("result-receipts").innerHTML = [[me, "あなた"], [opp, cpu ? "コンピュータ" : "相手"]]
      .map(([seat, label]) => `<article class="receipt-tile"><h3>${esc(label)}のカードの元レシート</h3>${receiptHtml(b.receipts[seat])}</article>`).join("");
    $("btn-rematch").disabled = false;
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
      try { await navigator.clipboard.writeText(state.code); $("btn-copy").textContent = "コピー済み"; } catch (_) { $("btn-copy").textContent = "コピーできません"; }
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
      if (state.replay && state.replay.step < state.replay.battle.log.length) {
        clearInterval(state.replay.timer);
        state.replay.timer = setInterval(playStep, SPEEDS[state.speedIndex][1]);
      }
    });
    $("btn-skip").addEventListener("click", skipBattle);
    $("btn-rematch").addEventListener("click", rematch);

    resume();
  }

  init();
})();
