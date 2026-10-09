// デモ用のレシートデータ（架空）。本番では receipt_structured_summary / receipt_structured_item から読む。
(function (root) {
  "use strict";
  const RECEIPTS = [
    { id: "R001", charCount: 310, storeName: "まるまるマート 本店", storeCategory: "スーパー", purchasedAt: "2026-10-03T10:12:00", totalAmount: 3480, items: [
      { name: "牛乳", category: "食料品", amount: 198 }, { name: "食パン", category: "食料品", amount: 168 }, { name: "国産豚肉", category: "食料品", amount: 1280 },
      { name: "洗濯洗剤", category: "日用品", amount: 498 }, { name: "トイレットペーパー", category: "日用品", amount: 698 }, { name: "卵", category: "食料品", amount: 238 }] },
    { id: "R002", charCount: 140, storeName: "コンビニ ハッピー駅前店", storeCategory: "コンビニ", purchasedAt: "2026-10-04T06:48:00", totalAmount: 777, items: [
      { name: "おにぎり", category: "食料品", amount: 150 }, { name: "缶コーヒー", category: "飲食", amount: 130 }, { name: "サンドイッチ", category: "食料品", amount: 298 }, { name: "ガム", category: "食料品", amount: 199 }] },
    { id: "R003", charCount: 265, storeName: "ドラッグ青空", storeCategory: "ドラッグストア", purchasedAt: "2026-10-04T20:31:00", totalAmount: 5860, items: [
      { name: "ボディソープ詰替", category: "日用品", amount: 2980 }, { name: "歯みがき粉", category: "日用品", amount: 398 }, { name: "ビタミン剤", category: "その他", amount: 1980 }, { name: "ティッシュ", category: "日用品", amount: 502 }] },
    { id: "R004", charCount: 120, storeName: "食堂ひなた", storeCategory: "飲食店", purchasedAt: "2026-10-05T12:05:00", totalAmount: 1650, items: [
      { name: "醤油ラーメン", category: "飲食", amount: 980 }, { name: "餃子", category: "飲食", amount: 420 }, { name: "ウーロン茶", category: "飲食", amount: 250 }] },
    { id: "R005", charCount: 180, storeName: "高速道路サービス 湾岸", storeCategory: "その他", purchasedAt: "2026-10-05T23:40:00", totalAmount: 12400, items: [
      { name: "高速料金", category: "交通・移動", amount: 8200 }, { name: "ガソリン", category: "交通・移動", amount: 4000 }, { name: "ホットドッグ", category: "飲食", amount: 200 }] },
    { id: "R006", charCount: 520, storeName: "まるまるマート 大型店", storeCategory: "スーパー", purchasedAt: "2026-10-06T17:20:00", totalAmount: 22222, items: [
      { name: "和牛ステーキ肉", category: "食料品", amount: 9800 }, { name: "ズワイガニ", category: "食料品", amount: 4200 }, { name: "米 10kg", category: "食料品", amount: 3980 }, { name: "洗剤セット", category: "日用品", amount: 1480 },
      { name: "ワイン", category: "飲食", amount: 1280 }, { name: "チーズ", category: "食料品", amount: 680 }, { name: "果物詰合せ", category: "食料品", amount: 480 }, { name: "ペーパータオル", category: "日用品", amount: 322 }] },
    { id: "R007", charCount: 330, storeName: "居酒屋 夜のとばり", storeCategory: "飲食店", purchasedAt: "2026-10-06T22:15:00", totalAmount: 8900, items: [
      { name: "刺身盛り合わせ", category: "飲食", amount: 2400 }, { name: "焼き鳥盛り", category: "飲食", amount: 1800 }, { name: "生ビール", category: "飲食", amount: 2100 }, { name: "唐揚げ", category: "飲食", amount: 780 }, { name: "お通し", category: "飲食", amount: 600 }] },
    { id: "R008", charCount: 95, storeName: "コンビニ ハッピー空港店", storeCategory: "コンビニ", purchasedAt: "2026-10-07T02:30:00", totalAmount: 640, items: [
      { name: "カップ麺", category: "食料品", amount: 240 }, { name: "ペットボトル茶", category: "飲食", amount: 160 }, { name: "電池", category: "日用品", amount: 240 }] },
    { id: "R009", charCount: 210, storeName: "ドラッグ青空 駅南店", storeCategory: "ドラッグストア", purchasedAt: "2026-10-07T09:02:00", totalAmount: 1980, items: [
      { name: "マスク", category: "日用品", amount: 698 }, { name: "絆創膏", category: "日用品", amount: 328 }, { name: "のど飴", category: "食料品", amount: 258 }, { name: "目薬", category: "その他", amount: 696 }] },
    { id: "R010", charCount: 150, storeName: "雑貨店 ことり", storeCategory: "その他", purchasedAt: "2026-10-07T15:48:00", totalAmount: 4310, items: [
      { name: "マグカップ", category: "日用品", amount: 1650 }, { name: "ブランケット", category: "日用品", amount: 1980 }, { name: "キャンドル", category: "日用品", amount: 680 }] },
    // 以下の2件はデモの開始時点で「カード未生成」。4:05バッチの模擬実行で生成される。
    { id: "R011", charCount: 170, storeName: "まるまるマート 本店", storeCategory: "スーパー", purchasedAt: "2026-10-08T18:05:00", totalAmount: 1111, pending: true, items: [
      { name: "豆腐", category: "食料品", amount: 98 }, { name: "ねぎ", category: "食料品", amount: 128 }, { name: "鮭切身", category: "食料品", amount: 580 }, { name: "味噌 会員番号 4000100123", category: "食料品", amount: 305 }] },
    { id: "R012", charCount: 200, storeName: "食堂ひなた", storeCategory: "飲食店", purchasedAt: "2026-10-08T07:41:00", totalAmount: 2400, pending: true, items: [
      { name: "朝定食", category: "飲食", amount: 1200 }, { name: "焼き魚", category: "飲食", amount: 700 }, { name: "味噌汁", category: "飲食", amount: 200 }, { name: "緑茶", category: "飲食", amount: 300 }] }
  ];

  // レシートの全項目を表示するための補足項目（本番は receipt_structured_summary／item にある値）
  const PAYMENTS = ["現金", "クレジットカード", "電子マネー", "QR決済"];
  RECEIPTS.forEach((r, i) => {
    r.branchName = r.storeName.includes(" ") ? r.storeName.split(" ").slice(1).join(" ") : "";
    r.storeName = r.storeName.split(" ")[0];
    r.paymentMethod = PAYMENTS[(i * 3 + 1) % PAYMENTS.length];
    r.receiptNumber = String(1000 + i * 37).padStart(6, "0");
    r.items.forEach((it) => { it.quantity = 1; it.unitPrice = it.amount; });
    // 解析で得た行テキスト（本番は receipt テーブルの text 列。電話番号などもそのまま入る）
    const d = r.purchasedAt;
    r.lines = [
      `${r.storeName} ${r.branchName}`.trim(),
      `東京都架空区サンプル町${i + 1}-${i + 2}-${i + 3}`,
      `TEL 03-0000-${String(1000 + i * 11)}（架空）`,
      `${d.slice(0, 4)}年${d.slice(5, 7)}月${d.slice(8, 10)}日 ${d.slice(11, 16)}`,
      `レシートNo ${r.receiptNumber}　担当 ${String.fromCharCode(65 + (i % 26))}${10 + i}`,
      "------------------------------",
      ...r.items.map((it) => `${it.name}　${it.quantity}点　¥${it.amount.toLocaleString("ja-JP")}`),
      "------------------------------",
      `小計　¥${r.totalAmount.toLocaleString("ja-JP")}`,
      `合計　¥${r.totalAmount.toLocaleString("ja-JP")}`,
      `お支払い　${r.paymentMethod}`,
      `会員番号 ${String(4000100 + i * 13)}（架空）　ポイント ${Math.floor(r.totalAmount / 100)}pt`,
      "ご利用ありがとうございました。またのご来店をお待ちしております。",
      "営業時間 9:00〜22:00　年中無休　お問い合わせは店頭まで"
    ];
    r.charCount = r.lines.join("").replace(/\s/g, "").length;
  });

  // 「画像から生成」の模擬解析用（本番はGeminiがレシート画像を読む）
  const STORE_POOL = [
    ["みなと市場", "スーパー"], ["コンビニ ハッピー", "コンビニ"], ["ドラッグ青空", "ドラッグストア"], ["麺処 こがらし", "飲食店"], ["古書と雑貨 ひだまり", "その他"]
  ];
  const ITEM_POOL = [
    ["鶏むね肉", "食料品", 398], ["キャベツ", "食料品", 198], ["ヨーグルト", "食料品", 168], ["焼きそば", "飲食", 620], ["カレーライス", "飲食", 880], ["生ビール", "飲食", 540],
    ["食器用洗剤", "日用品", 248], ["ゴミ袋", "日用品", 398], ["シャンプー", "日用品", 880], ["タクシー代", "交通・移動", 2400], ["ICカードチャージ", "交通・移動", 3000], ["文房具", "その他", 330]
  ];

  root.MonsterMock = { RECEIPTS, STORE_POOL, ITEM_POOL };
  if (typeof module !== "undefined" && module.exports) module.exports = root.MonsterMock;
})(typeof window !== "undefined" ? window : globalThis);
