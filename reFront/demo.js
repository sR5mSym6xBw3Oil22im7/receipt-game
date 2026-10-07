const demoReceipt = {
  storeName: "サンプルストア",
  purchasedAt: "2026/09/15 12:34",
  items: [
    { name: "牛乳", price: 218 },
    { name: "食パン", price: 168 },
    { name: "卵", price: 298 },
    { name: "コーヒー", price: 398 }
  ],
  total: 1082,
  paymentMethod: "クレジットカード"
};

const DEMO_ANALYSIS_DELAY_MS = 900;
const analyzeButton = document.getElementById("demo-analyze-button");
const statusElement = document.getElementById("demo-status");
const resultCard = document.getElementById("demo-result-card");
const resultElement = document.getElementById("demo-result");

function yen(value) {
  return `${value.toLocaleString("ja-JP")}円`;
}

function buildDemoLines() {
  return [
    `店舗名：${demoReceipt.storeName}`,
    `購入日時：${demoReceipt.purchasedAt}`,
    "",
    "商品：",
    ...demoReceipt.items.map((item) => `${item.name}　　　　${yen(item.price)}`),
    "",
    `合計金額：${yen(demoReceipt.total)}`,
    `支払方法：${demoReceipt.paymentMethod}`
  ];
}

function renderDemoResult() {
  const result = document.createElement("article");
  result.className = "receipt-paper";

  const heading = document.createElement("h3");
  heading.textContent = "サンプルレシート";

  const text = document.createElement("pre");
  text.textContent = buildDemoLines().join("\n");

  result.append(heading, text);
  resultElement.replaceChildren(result);
  resultCard.classList.remove("hidden");
}

analyzeButton.addEventListener("click", () => {
  analyzeButton.disabled = true;
  analyzeButton.classList.add("is-busy");
  resultCard.classList.add("hidden");
  statusElement.classList.remove("error-message");
  statusElement.textContent = "解析中...（デモ用データを準備しています）";

  window.setTimeout(() => {
    renderDemoResult();
    statusElement.textContent = "デモ解析が完了しました。";
    analyzeButton.disabled = false;
    analyzeButton.classList.remove("is-busy");
    resultCard.scrollIntoView({ behavior: "smooth", block: "start" });
  }, DEMO_ANALYSIS_DELAY_MS);
});
