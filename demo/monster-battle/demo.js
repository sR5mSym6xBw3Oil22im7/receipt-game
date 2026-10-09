// モンスターレシート対戦 デモの画面制御。
// 本番では「相手プレイヤー」は別PCの人間で、ルーム・カード・対戦結果はサーバーが管理する。
// ここでは相手を自動操作にして、1つのブラウザーで一通りの流れを体験できるようにしている。
(function () {
  "use strict";
  const E = window.MonsterEngine;
  const M = window.MonsterMock;
  const $ = (id) => document.getElementById(id);
    const CPU_WAIT_SECONDS = 12;   // 本番の既定は90秒（設定で変更可）。デモでは短くしている
  const SPEEDS = [["ふつう", 1100], ["はやい", 450], ["ゆっくり", 1900]];

  const state = {
    pool: [], nextId: 1, seat: "P1", roomCode: "", timers: [],
    myId: null, myConfirmed: false, oppId: null, oppConfirmed: false,
    speedIndex: 0, replay: null, mode: "HUMAN", cpuReason: "", myField: [], oppField: [], flipped: new Set(), hand: [], genIds: []
  };

  // ---------- 共通 ----------
  function esc(s) {
    return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }
  const yen = (n) => "¥" + Number(n).toLocaleString("ja-JP");
  const later = (fn, ms) => { const t = setTimeout(fn, ms); state.timers.push(t); return t; };
  function clearTimers() { state.timers.forEach(clearTimeout); state.timers = []; if (state.replay) { clearInterval(state.replay.timer); state.replay = null; } }
  const cardById = (id) => state.pool.find((c) => c.id === id);
  const randomU32 = () => crypto.getRandomValues(new Uint32Array(1))[0];

  function addCard(card) { card.id = state.nextId++; state.pool.unshift(card); return card; }

  function setSteps(id, index) {
    [...$(id).children].forEach((li, i) => { li.classList.toggle("is-done", i < index); li.classList.toggle("is-active", i === index); });
  }

  function show(view) {
    document.querySelectorAll(".view").forEach((s) => s.classList.toggle("hidden", s.id !== "view-" + view));
    window.scrollTo({ top: 0 });
  }

  // ---------- カード表示 ----------
  function cardHtml(card, opt) {
    opt = opt || {};
    const art = opt.hidden
      ? `<div class="mc-art is-hidden" aria-label="相手のカード（対戦開始まで非公開）">?</div>`
      : `<div class="mc-art"><span class="mc-elem" title="属性">${esc(card.element)}</span>${card.lucky ? '<span class="mc-zoro">ラッキー</span>' : ""}<img alt="${esc(card.name)}のイラスト" src="${E.svgDataUri(card.svg)}"></div>`;
    if (opt.hidden) return `<div class="mcard"><div class="mc-head"><span class="mc-name">？？？</span></div>${art}<div class="mc-foot">対戦が始まるまで非公開</div></div>`;
    const tag = opt.clickable ? "button" : "div";
    const cls = ["mcard", "e-" + card.element, "r-" + card.rarity, opt.selected ? "is-selected" : "", opt.clickable ? "is-clickable" : ""].join(" ");
    const attrs = opt.clickable ? ` type="button" data-card="${card.id}"` : "";
    const src = { ANALYZE: "解析で生成", PLAYER1: "P1が生成", PLAYER2: "P2が生成" }[card.source] || card.source;
    return `<${tag} class="${cls}"${attrs}>
      <div class="mc-head"><span class="mc-name">${esc(card.name)}</span><span class="mc-rarity">${card.rarity}</span></div>
      ${art}
      <dl class="mc-stats"><div><dt>HP</dt><dd>${card.hp}</dd></div><div><dt>ATK</dt><dd>${card.atk}</dd></div><div><dt>DEF</dt><dd>${card.def}</dd></div><div><dt>SPD</dt><dd>${card.spd}</dd></div><div><dt>LUCK</dt><dd>${card.luck}</dd></div></dl>
      <div class="mc-skill">必殺技：${esc(card.skillName)} ×${card.skillPower.toFixed(2)}</div>
      <p class="mc-flavor">${esc(card.flavor)}</p>
      <div class="mc-foot">${esc(card.storeCategory)}のレシート<span class="src">${esc(src)}</span></div>
    </${tag}>`;
  }

  // ---------- M01 ロビー / M02 待機 ----------
  function newRoomCode() {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    return Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => alphabet[b % alphabet.length]).join("");
  }

  function resetRoom() {
    clearTimers();
    state.pool = state.pool.filter((c) => !c.source.startsWith("PLAYER"));   // 画像から作ったカードはルームと一緒に消える
    state.myId = null; state.myConfirmed = false; state.oppId = null; state.oppConfirmed = false; 
    state.mode = "HUMAN"; state.cpuReason = "";
    show("lobby");
  }

  function setSeatRow(id, name, badge, empty) {
    const li = $(id);
    li.classList.toggle("is-empty", !!empty);
    li.querySelector(".seat-name").textContent = name;
    li.querySelector(".badge").textContent = badge;
  }

  function enterWait(seat, code) {
    clearTimers();
    state.seat = seat; state.roomCode = code; state.mode = "HUMAN"; state.cpuReason = "";
    state.myId = null; state.myConfirmed = false; state.oppId = null; state.oppConfirmed = false; 
    $("room-code").textContent = code;
    show("wait");
    const opp = "相手プレイヤー（自動操作）";
    const mine = seat === "P1" ? "seat-p1" : "seat-p2", theirs = seat === "P1" ? "seat-p2" : "seat-p1";
    setSeatRow(mine, "あなた", "参加済み", false);
    $("demo-join-row").classList.toggle("hidden", seat !== "P1");
    if (seat === "P1") {
      setSeatRow(theirs, "参加を待っています…", "待機中", true);
      let left = CPU_WAIT_SECONDS;
      const tick = () => {
        if (left <= 0) { startCpuMode(theirs); return; }
        $("wait-status").textContent = `ルームコードを相手に伝えてください。あと ${left} 秒で、参加がなければコンピュータ（P2）との対戦になります。`;
        left--;
        later(tick, 1000);
      };
      tick();
    } else {
      setSeatRow(theirs, opp, "参加済み", false);
      $("wait-status").textContent = "ルームに参加しました。カード選択へ進みます…";
      later(enterSelect, 1300);
    }
  }

  function humanJoined() {
    clearTimers();
    setSeatRow("seat-p2", "相手プレイヤー（自動操作）", "参加済み", false);
    $("wait-status").textContent = "2人そろいました。カード選択へ進みます…";
    $("demo-join-row").classList.add("hidden");
    later(enterSelect, 1300);
  }

  // 一定時間たってもP2が参加しない場合は、コンピュータ（P2）との対戦にする
  function startCpuMode(theirs) {
    state.mode = "CPU";
    setSeatRow(theirs, "コンピュータ（自動）", "CPU", false);
    $("demo-join-row").classList.add("hidden");
    $("wait-status").textContent = "相手が参加しなかったため、コンピュータ対戦になりました。カード選択へ進みます…";
    later(enterSelect, 1500);
  }

  // ---------- M03 / M04 レシート1枚から始めて、最大3枚から1枚を選ぶ ----------
  const HAND_LIMIT = 3;    // 1プレイで持てる（選べる）カードの上限。画像から作るカードも含む
  const dealable = () => state.pool.filter((c) => !c.source.startsWith("PLAYER"));
  const shuffled = (list) => { const a = [...list]; for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
  const totalReceipts = () => state.hand.length;
  const FIELD_SIZE = 9;       // 画面に並べるレシートの枚数（3x3）
  const SHUFFLE_MIN = 10;     // DBのレシートがこの数以上のときだけシャッフルボタンを出す
  // 表示用の9枚（DBからランダム）。相手の配布分と、すでに手札に入れたカードは除く
  const pickField = () => shuffled(dealable().filter((c) => !state.oppField.includes(c) && !state.hand.includes(c.id))).slice(0, FIELD_SIZE);
  // まだ誰にも配られていない有効カード（2人の配布は重ならない）
  const undealt = () => dealable().filter((c) => !state.myField.includes(c) && !state.oppField.includes(c));

  // コンピュータの思考（本番は GEMINI_API_PLAYER2 のキーでGeminiが選ぶ。デモでは簡易ルールで代用）。
  // 人間と同じく、レシート1枚から始めて追加で引き（合計3枚まで）、その手札から1枚だけ選ぶ。DBの既存カードだけを使い、新しいカードは作らない。
  function cpuThink() {
    const want = 1 + Math.floor(Math.random() * HAND_LIMIT);   // 本番はサーバーが1〜12の乱数で決める
    while (state.oppField.length < want && undealt().length) state.oppField.push(shuffled(undealt())[0]);
    const hand = state.oppField;
    if (!hand.length) return null;
    const pick = [...hand].sort((a, b) => b.power - a.power)[0];
    const ratios = [["HP", pick.hp / 700, "粘り強く戦えるため"], ["ATK", pick.atk / 140, "攻撃力が高く、短期決戦を狙えるため"], ["SPD", pick.spd / 100, "素早く、先手を取りやすいため"], ["DEF", pick.def / 110, "防御が堅く、削られにくいため"]];
    ratios.sort((a, b) => b[1] - a[1]);
    return { card: pick, reason: `手札${hand.length}枚の中から「${pick.name}」（${pick.element}・POWER ${pick.power}）を選びました。${ratios[0][2]}。` };
  }

  function enterSelect() {
    clearTimers();
    const cards = shuffled(dealable());
    if (cards.length < 2) { window.alert("対戦できるカードが足りません（有効なカードが2枚以上必要です）。ロビーへ戻ります。"); resetRoom(); return; }
    state.oppField = [cards[0]];                      // 相手に配るレシート
    state.flipped = new Set();                        // 選んだ（めくった）レシートの番号
    state.genIds = [];                                // 画像から作ったカードのID
    state.hand = [];                                  // 手札（めくった・作ったカードのID）
    state.myField = pickField();                      // 自分に見せるレシート（最大9枚）
    state.myId = null; state.myConfirmed = false; state.oppId = null; state.oppConfirmed = false; state.cpuReason = "";
    show("select");
    setSteps("steps", 1);
    $("seat-badge").textContent = "あなたは " + state.seat;
    switchSub("pool");
    $("gen-status").textContent = "";
    $("gen-preview").innerHTML = "";
    $("btn-confirm").disabled = true;
    $("btn-confirm").textContent = "このカードで決定";
    renderSelect();
    updateChosen();
    if (state.mode === "CPU") {
      $("opponent-state").textContent = "コンピュータ（P2）：あなたの決定を待っています（DB上のカードから選びます）";
      return;
    }
    $("opponent-state").textContent = "相手：レシートを見ています…";
    // 相手の自動操作：数秒後に、手札の中から1枚を選んで確定する
    later(() => {
      const extra = Math.floor(Math.random() * HAND_LIMIT);
      for (let i = 0; i < extra && undealt().length; i++) state.oppField.push(shuffled(undealt())[0]);
      const pick = state.oppField[Math.floor(Math.random() * state.oppField.length)];
      state.oppId = pick.id; state.oppConfirmed = true;
      $("opponent-state").textContent = "相手：カードを確定しました（内容は対戦開始まで非公開）";
      maybeStart();
    }, 3200 + Math.random() * 2000);
  }

  const yenOrBlank = (n) => (n == null ? "" : yen(n));
  // 配られたレシート。内容はすべて表示する（見られるのは配られた本人だけ。本番もサーバーが本人にだけ返す）
  function receiptHtml(card, opt) {
    opt = opt || {};
    const r = card.receipt;
    const items = r.items.map((it) => `<tr><td>${esc(it.name)}</td><td class="q">${it.quantity}</td><td class="n">${yenOrBlank(it.unitPrice)}</td><td class="n">${yenOrBlank(it.amount)}</td></tr>`).join("");
    return `<span class="rt-no">${opt.title ? esc(opt.title) : ""} <em>${esc(r.storeCategory)}</em></span>
      <span class="rt-store">${esc(r.storeName)}${r.branchName ? " " + esc(r.branchName) : ""}</span>
      <span class="rt-meta">${esc(r.purchasedAt.replace("T", " ").slice(0, 16))}　レシート番号 ${esc(r.receiptNumber)}</span>
      <table class="rt-items"><thead><tr><th>商品</th><th class="q">数</th><th class="n">単価</th><th class="n">金額</th></tr></thead><tbody>${items}</tbody></table>
      <span class="rt-total"><span>合計</span><b>${yen(card.totalAmount)}</b></span>
      <span class="rt-meta">お支払い：${esc(r.paymentMethod)}</span>`;
  }

  // 配られたレシート。内容はすべて表示する（見られるのは、そのルームのプレイヤーだけ。対戦前は配られた本人だけ）
  const handFull = () => state.hand.length >= HAND_LIMIT;

  function receiptTileHtml(card, index) {
    const flipped = state.flipped.has(index);
    return `<article class="receipt-tile ${flipped ? "is-flipped" : ""}" aria-label="レシート ${index + 1}">
      ${receiptHtml(card, { title: "レシート No." + (index + 1) })}
      <button type="button" class="${flipped ? "secondary-button" : "primary-button"} small-button" data-flip="${index}" ${flipped || state.myConfirmed || handFull() ? "disabled" : ""}>${flipped ? "モンスターを呼び出しました" : "▶ このレシートを選ぶ"}</button>
    </article>`;
  }

  function renderSelect() {
    $("flip-info").textContent = `最初のレシートは1枚です。レシートを選ぶと、モンスターカードが現れます。手札は最大${HAND_LIMIT}枚です。手札の中から1枚を決めて対戦します。`;
    $("receipt-field").innerHTML = state.myField.map((c, i) => receiptTileHtml(c, i)).join("");
    const canShuffle = dealable().length >= SHUFFLE_MIN;
    $("btn-shuffle").classList.toggle("hidden", !canShuffle);
    $("btn-shuffle").disabled = state.myConfirmed || state.hand.length > 0;   // 1枚でも選んだらシャッフル不可
    const imageLocked = state.myConfirmed || handFull();                      // 手札が満杯なら画像からも作れない
    $("image-input").disabled = imageLocked;
    $("drop-zone").classList.toggle("is-disabled", imageLocked);
    document.querySelectorAll(".subtab").forEach((b) => { b.disabled = imageLocked; });
    $("draw-info").textContent = `DB上のレシートからランダムに${state.myField.length}枚を表示しています。`;
    $("hand-title").textContent = `手札（${state.hand.length} / ${HAND_LIMIT}枚）— この中から1枚を選びます`;
    $("hand-grid").innerHTML = state.hand.length
      ? state.hand.map((id) => { const c = cardById(id); return cardHtml(c, { clickable: !state.myConfirmed, selected: c.id === state.myId }); }).join("")
      : '<div class="hand-slot">まだモンスターは現れていません<br><small>レシートを選ぶと、ここに現れます</small></div>';
  }

  function shuffleReceipts() {
    if (state.myConfirmed) return;
    state.myField = pickField();
    state.flipped = new Set();
    renderSelect();
  }

  function flipReceipt(index) {
    if (state.myConfirmed || state.flipped.has(index) || state.hand.length >= HAND_LIMIT) return;
    state.flipped.add(index);
    const card = state.myField[index];
    state.hand.push(card.id);
    setGenStatus("", false);
    renderSelect();
    updateChosen();
    const el = $("hand-grid").querySelector(`[data-card="${card.id}"]`);
    if (el) el.classList.add("is-revealed");
  }

  function updateChosen() {
    const c = cardById(state.myId);
    $("chosen-info").textContent = c ? `選択中：${c.name}（${c.element}・${c.rarity}・POWER ${c.power}）` : state.hand.length ? "手札から、対戦に使う1枚を選んでください。" : "レシートを選んで、モンスターを呼び出してください。";
    $("btn-confirm").disabled = !c || state.myConfirmed;
  }

  function setGenStatus(text, isError) {
    $("gen-status").textContent = text;
    $("gen-status").classList.toggle("is-error", !!isError);
  }

  function switchSub(name) {
    document.querySelectorAll(".subtab").forEach((b) => b.classList.toggle("is-active", b.dataset.sub === name));
    $("sub-pool").classList.toggle("hidden", name !== "pool");
    $("sub-image").classList.toggle("hidden", name !== "image");
  }

  function confirmCard() {
    const c = cardById(state.myId);
    if (!c || state.myConfirmed || !state.hand.includes(c.id) && !state.genIds.includes(c.id)) return;
    state.myConfirmed = true;
    $("btn-confirm").disabled = true;
    $("btn-confirm").textContent = "決定済み";
    $("chosen-info").textContent = `決定：${c.name}。${state.oppConfirmed ? "" : "相手の確定を待っています…"}`;
    renderSelect();
    maybeStart();
  }

  function maybeStart() {
    if (state.mode === "CPU" && state.myConfirmed && !state.oppConfirmed) {
      $("opponent-state").textContent = "コンピュータ（P2）：レシートを見て考えています…（あなたのカードは知らせずに選びます）";
      later(() => {
        const t = cpuThink();
        if (!t) { setGenStatus("コンピュータが選べるカードがありません。ロビーへ戻ってください。", true); return; }
        state.oppId = t.card.id; state.oppConfirmed = true; state.cpuReason = t.reason;
        $("opponent-state").textContent = "コンピュータ（P2）：カードを確定しました（内容は対戦開始まで非公開）";
        maybeStart();
      }, 1800);
      return;
    }
    if (state.myConfirmed && state.oppConfirmed) { $("chosen-info").textContent = "両者のカードが決まりました。対戦開始！"; later(startBattle, 1200); }
  }

  async function sha256Hex(file) {
    try {
      const buf = await file.arrayBuffer();
      const digest = await crypto.subtle.digest("SHA-256", buf);
      return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
    } catch (_) {
      return E.fakeSha(file.name + file.size);
    }
  }

  // 本番ではGeminiが画像から読み取る。デモでは画像のハッシュから架空のレシートを作る。
  function pseudoReceipt(sha) {
    const rng = E.mulberry32(E.seedFromSha(sha));
    const [storeName, storeCategory] = M.STORE_POOL[Math.floor(rng() * M.STORE_POOL.length)];
    const count = 3 + Math.floor(rng() * 6);
    const items = Array.from({ length: count }, () => {
      const [name, category, price] = M.ITEM_POOL[Math.floor(rng() * M.ITEM_POOL.length)];
      return { name, category, amount: price * (1 + Math.floor(rng() * 3)) };
    });
    const hour = String(Math.floor(rng() * 24)).padStart(2, "0");
    const minute = String(Math.floor(rng() * 60)).padStart(2, "0");
    const day = String(1 + Math.floor(rng() * 28)).padStart(2, "0");
    return { storeName, storeCategory, purchasedAt: `2026-10-${day}T${hour}:${minute}:00`, charCount: 90 + Math.floor(rng() * 450), totalAmount: items.reduce((s, i) => s + i.amount, 0), items };
  }

  async function onImagePicked(file) {
    if (!file) return;
    if (!/^image\/(jpeg|png)$/.test(file.type)) { setGenStatus("JPEGまたはPNGの画像を選んでください。", true); return; }
    if (file.size > 5 * 1024 * 1024) { setGenStatus("画像は5 MiB以下にしてください。", true); return; }
    if (state.myConfirmed) { setGenStatus("カードは決定済みです。", true); return; }
    setGenStatus("レシートを解析して、カードを作っています…（模擬）", false);
    $("gen-preview").innerHTML = "";
    const sha = await sha256Hex(file);
    await new Promise((r) => later(r, 1100));
    const existing = state.pool.find((c) => c.sha === sha);
    let card;
    if (existing) {
      if (state.oppField.some((c) => c.id === existing.id) || state.myField.some((c) => c.id === existing.id) || state.hand.includes(existing.id)) { setGenStatus("このレシートのカードは、すでに使われています。別の画像を選んでください。", true); return; }
      card = existing;
      setGenStatus("この画像のカードは作成済みです。新しく作らず、既存のカードを使います。", false);
    } else {
      card = addCard(E.generateCard(pseudoReceipt(sha), sha, state.seat === "P1" ? "PLAYER1" : "PLAYER2"));
      setGenStatus("カードができました！このカードで対戦できます。", false);
    }
    state.genIds = [card.id];   // 画像から作るカードは1体のみ表示（手札の3枚には数えない）
    state.myId = card.id;
    $("gen-preview").innerHTML = `<div class="card-grid">${cardHtml(card, { selected: true })}</div>`;
    updateChosen();
    renderSelect();
  }

  // ---------- M05 対戦 ----------
  function fighterHtml(card, slot) {
    return `<div class="hp-box"><div class="hp-label"><span>${slot === 0 ? "あなた" : state.mode === "CPU" ? "コンピュータ" : "相手"}</span><span class="hp-num">${card.hp} / ${card.hp}</span></div><div class="hp-bar"><i></i></div></div>${cardHtml(card)}`;
  }

  function startBattle() {
    show("battle");
    setSteps("steps-battle", 2);
    const my = cardById(state.myId), opp = cardById(state.oppId);
    const meIndex = state.seat === "P1" ? 0 : 1;
    const cards = meIndex === 0 ? [my, opp] : [opp, my];   // [P1のカード, P2のカード]
    const seed = randomU32();
    const result = E.battle(cards[0], cards[1], seed);
    const slotOf = (index) => (index === meIndex ? 0 : 1);   // 画面の左＝自分、右＝相手
    const slotCards = [my, opp];
    state.replay = { result, slotOf, slotCards, step: 0, hp: [my.hp, opp.hp], timer: null, meIndex };
    $("battle-seed").textContent = "battleSeed " + seed;
    $("cpu-note").classList.toggle("hidden", state.mode !== "CPU");
    $("cpu-note").textContent = state.mode === "CPU" ? "コンピュータ（P2）の思考：" + state.cpuReason : "";
    $("fighter-0").innerHTML = fighterHtml(my, 0);
    $("fighter-1").innerHTML = fighterHtml(opp, 1);
    ["fighter-0", "fighter-1"].forEach((id) => $(id).className = "fighter");
    $("fighter-0").style.setProperty("--lunge", "24px");
    $("fighter-1").style.setProperty("--lunge", "-24px");
    $("battle-log").innerHTML = "";
    const first = slotOf(result.first);
    appendLog(`先攻：${slotCards[first].name}（素早さ ${slotCards[first].spd}）`);
    state.replay.timer = setInterval(playStep, SPEEDS[state.speedIndex][1]);
  }

  function appendLog(text, who, tags) {
    const li = document.createElement("li");
    if (who !== undefined) { const s = document.createElement("span"); s.className = "who" + who; s.textContent = text.split("の")[0]; li.append(s, document.createTextNode("の" + text.split("の").slice(1).join("の"))); }
    else li.textContent = text;
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
    if (r.step >= r.result.log.length) { finishBattle(); return; }
    const e = r.result.log[r.step++];
    const att = r.slotOf(e.actor), def = r.slotOf(e.target);
    r.hp[def] = e.hpAfter;
    setHp(def, e.hpAfter, r.slotCards[def].hp);
    const attBox = $("fighter-" + att), defBox = $("fighter-" + def);
    if (!instant) {
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
    appendLog(`${r.slotCards[att].name}の${e.skillName}！ ${e.damage}ダメージ`, att, tags);
    if (e.hpAfter === 0) defBox.classList.add("is-defeated");
    if (r.step >= r.result.log.length) { clearInterval(r.timer); if (!instant) later(finishBattle, 1300); }
  }

  function skipBattle() {
    const r = state.replay;
    if (!r) return;
    clearInterval(r.timer);
    while (r && r.step < r.result.log.length) playStep(true);
    finishBattle();
  }

  // ---------- M06 結果 ----------
  function finishBattle() {
    const r = state.replay;
    if (!r) return;
    clearInterval(r.timer);
    const { result, slotCards, meIndex } = r;
    state.replay = null;
    show("result");
    setSteps("steps-result", 3);
    const draw = result.winner === "DRAW";
    const iWon = !draw && result.winner === meIndex;
    const banner = $("result-banner");
    banner.textContent = draw ? "DRAW" : iWon ? "YOU WIN!" : "YOU LOSE…";
    banner.classList.toggle("is-lose", !iWon);
    const winnerName = draw ? "" : slotCards[iWon ? 0 : 1].name;
    $("result-detail").textContent = (state.mode === "CPU" ? "【コンピュータ対戦】" : "") + (draw ? "引き分けです。" : `${winnerName}の勝利！`) +
      (result.reason === "KO" ? `（KO決着・${result.log.length}回の行動）` : "（20ラウンド終了：残りHP割合で判定）") +
      ` 同じ battleSeed（${result.battleSeed}）で再計算すると、必ず同じ結果になります。`;
    $("result-arena").innerHTML = `<div class="fighter ${iWon ? "is-winner" : draw ? "" : "is-defeated"}">${cardHtml(slotCards[0])}</div><div class="vs" aria-hidden="true">VS</div><div class="fighter ${!iWon && !draw ? "is-winner" : draw ? "" : "is-defeated"}">${cardHtml(slotCards[1])}</div>`;
    $("result-receipts").innerHTML = slotCards.map((c, i) => `<article class="receipt-tile"><h3>${i === 0 ? "あなた" : state.mode === "CPU" ? "コンピュータ" : "相手"}のカードの元レシート</h3>${receiptHtml(c)}</article>`).join("");
  }

  // ---------- 初期化 ----------
  function init() {
    // デモ用の乱数の種はレシートごとに固定（同じレシート＝同じカード）
    M.RECEIPTS.forEach((r) => { r.sha = E.fakeSha(r.id + r.storeName + r.totalAmount); r.state = r.pending ? "none" : "saved"; });
    M.RECEIPTS.filter((r) => !r.pending).forEach((r) => addCard(E.generateCard(r, r.sha, "ANALYZE")));
    state.pool.reverse();   // 古い順に並べる

    $("btn-create").addEventListener("click", () => enterWait("P1", newRoomCode()));
    $("btn-join").addEventListener("click", () => {
      const raw = $("join-code").value.trim().toUpperCase();
      if (!/^[A-Z0-9]{6}$/.test(raw)) { $("join-code").setCustomValidity("6桁の英数字を入力してください"); $("join-code").reportValidity(); return; }
      $("join-code").setCustomValidity("");
      enterWait("P2", raw);
    });
    $("join-code").addEventListener("input", () => $("join-code").setCustomValidity(""));
    $("btn-copy").addEventListener("click", async () => {
      try { await navigator.clipboard.writeText(state.roomCode); $("btn-copy").textContent = "コピー済み"; } catch (_) { $("btn-copy").textContent = "コピーできません"; }
      setTimeout(() => { $("btn-copy").textContent = "コピー"; }, 1500);
    });
    $("btn-leave-wait").addEventListener("click", resetRoom);
    $("btn-demo-join").addEventListener("click", humanJoined);

    document.querySelectorAll(".subtab").forEach((b) => b.addEventListener("click", () => switchSub(b.dataset.sub)));
    $("btn-shuffle").addEventListener("click", shuffleReceipts);
    $("receipt-field").addEventListener("click", (ev) => {
      const b = ev.target.closest("[data-flip]");
      if (b) flipReceipt(Number(b.dataset.flip));
    });
    $("hand-grid").addEventListener("click", (ev) => {
      const b = ev.target.closest("[data-card]");
      if (!b || state.myConfirmed) return;
      state.myId = Number(b.dataset.card);
      updateChosen();
      renderSelect();
    });
    $("btn-confirm").addEventListener("click", confirmCard);
    $("image-input").addEventListener("change", (ev) => { onImagePicked(ev.target.files[0]); ev.target.value = ""; });
    const drop = $("drop-zone");
    ["dragover", "dragenter"].forEach((n) => drop.addEventListener(n, (ev) => { ev.preventDefault(); drop.classList.add("is-over"); }));
    ["dragleave", "drop"].forEach((n) => drop.addEventListener(n, () => drop.classList.remove("is-over")));
    drop.addEventListener("drop", (ev) => { ev.preventDefault(); onImagePicked(ev.dataTransfer.files[0]); });

    $("btn-speed").addEventListener("click", () => {
      state.speedIndex = (state.speedIndex + 1) % SPEEDS.length;
      $("btn-speed").textContent = "速度：" + SPEEDS[state.speedIndex][0];
      if (state.replay && state.replay.step < state.replay.result.log.length) {
        clearInterval(state.replay.timer);
        state.replay.timer = setInterval(playStep, SPEEDS[state.speedIndex][1]);
      }
    });
    $("btn-skip").addEventListener("click", skipBattle);
    $("btn-rematch").addEventListener("click", enterSelect);
    $("btn-lobby").addEventListener("click", resetRoom);

    show("lobby");
  }

  init();
})();
