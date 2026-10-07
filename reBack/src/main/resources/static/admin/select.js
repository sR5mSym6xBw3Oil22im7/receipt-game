const API_BASE_URL = window.APP_CONFIG?.API_BASE_URL ?? "http://localhost:8081";
const backLink = document.getElementById("back-link");
const listStatus = document.getElementById("list-status");
const receiptList = document.getElementById("receipt-list");
const detailPanel = document.getElementById("detail-panel");
const detailMeta = document.getElementById("detail-meta");
const detailBubble = document.getElementById("detail-bubble");
const closeDetail = document.getElementById("close-detail");
const deleteSelectedButton = document.getElementById("delete-selected");
const selectedTableNames = new Set();
let receiptCount = 0;
let openTableName = "";
const COIN_ICON = '<svg aria-hidden="true"><use href="#gd" width="34" height="34"/></svg>';
const EMPTY_ICON = '<svg width="56" height="56" aria-hidden="true"><use href="#gd" width="56" height="56"/></svg>';

function configureBackLink() {
  backLink.href = window.APP_CONFIG?.PUBLIC_BASE_URL ?? "https://sr5msym6xbw3oil22im7.github.io/";
}

function updateDeleteSelectedButton() {
  deleteSelectedButton.classList.toggle("hidden", receiptCount === 0);
  deleteSelectedButton.disabled = selectedTableNames.size === 0;
}
function formatDate(value) {
  return value ? new Date(value).toLocaleString("ja-JP") : "日時不明";
}

function makeBadge(text, className = "") {
  const badge = document.createElement("span");
  badge.className = `badge ${className}`.trim();
  badge.textContent = text;
  return badge;
}

function showDetail(detail) {
  openTableName = detail.tableName;
  detailMeta.replaceChildren(
    makeBadge(detail.tableName, "is-accent"),
    makeBadge(`${detail.lineCount}行`),
    makeBadge(formatDate(detail.createdAt))
  );
  detailBubble.replaceChildren();
  for (const line of detail.lines ?? []) {
    const lineElement = document.createElement("p");
    lineElement.className = "receipt-line";
    const lineNo = document.createElement("span");
    lineNo.className = "line-no";
    lineNo.textContent = `${line.lineNo}.`;
    const lineText = document.createElement("span");
    lineText.textContent = line.text;
    lineElement.append(lineNo, lineText);
    detailBubble.append(lineElement);
  }
  detailPanel.classList.remove("hidden");
  markOpenRow();
  detailPanel.scrollIntoView({ behavior: "smooth", block: "start" });
}

function markOpenRow() {
  for (const row of receiptList.querySelectorAll(".receipt-row")) {
    row.classList.toggle("is-open", row.dataset.tableName === openTableName);
  }
}

async function openDetail(tableName) {
  try {
    const response = await adminFetch(`${API_BASE_URL}/api/receipts/${encodeURIComponent(tableName)}`);
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
    showDetail(body);
  } catch (error) {
    listStatus.textContent = `エラー: ${error.message}`;
  }
}

function renderList(receipts) {
  receiptCount = receipts.length;
  receiptList.replaceChildren();
  if (!receipts.length) {
    listStatus.textContent = "保存済みのレシートはありません。";
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.innerHTML = `${EMPTY_ICON}<span>レシートを解析・保存すると、ここに表示されます。</span>`;
    receiptList.append(empty);
    updateDeleteSelectedButton();
    return;
  }
  listStatus.textContent = `${receipts.length}件のレシートがあります。`;
  for (const receipt of receipts) {
    const row = document.createElement("div");
    row.className = "receipt-row";
    row.dataset.tableName = receipt.tableName;
    row.classList.toggle("is-checked", selectedTableNames.has(receipt.tableName));
    row.classList.toggle("is-open", receipt.tableName === openTableName);
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.setAttribute("aria-label", `${receipt.tableName}を選択`);
    checkbox.checked = selectedTableNames.has(receipt.tableName);
    checkbox.addEventListener("change", () => {
      if (checkbox.checked) {
        selectedTableNames.add(receipt.tableName);
      } else {
        selectedTableNames.delete(receipt.tableName);
      }
      row.classList.toggle("is-checked", checkbox.checked);
      updateDeleteSelectedButton();
    });
    const icon = document.createElement("span");
    icon.className = "row-icon";
    icon.innerHTML = COIN_ICON;
    const title = document.createElement("strong");
    title.textContent = receipt.tableName;
    const meta = document.createElement("span");
    meta.className = "row-meta";
    const lineCount = document.createElement("span");
    lineCount.textContent = `${receipt.lineCount}行`;
    const createdAt = document.createElement("span");
    createdAt.textContent = formatDate(receipt.createdAt);
    meta.append(lineCount, createdAt);
    const info = document.createElement("div");
    info.className = "row-info";
    info.append(title, meta);
    const referenceButton = document.createElement("button");
    referenceButton.type = "button";
    referenceButton.className = "secondary-button small-button";
    referenceButton.textContent = "参照";
    referenceButton.addEventListener("click", () => openDetail(receipt.tableName));
    row.append(checkbox, icon, info, referenceButton);
    receiptList.append(row);
  }
  updateDeleteSelectedButton();
}

async function deleteSelectedReceipts() {
  if (!selectedTableNames.size) return;
  if (!window.confirm(`${selectedTableNames.size}件のレシートデータを削除しますか？`)) return;

  deleteSelectedButton.disabled = true;
  const tableNames = [...selectedTableNames];
  try {
    for (const tableName of tableNames) {
      const response = await adminFetch(`${API_BASE_URL}/api/receipts/${encodeURIComponent(tableName)}`, {
        method: "DELETE"
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
      selectedTableNames.delete(tableName);
    }
    detailPanel.classList.add("hidden");
    openTableName = "";
    listStatus.textContent = `${tableNames.length}件のレシートを削除しました。`;
    await loadReceipts();
  } catch (error) {
    listStatus.textContent = `削除エラー: ${error.message}`;
    updateDeleteSelectedButton();
  }
}

async function loadReceipts() {
  try {
    const response = await adminFetch(`${API_BASE_URL}/api/receipts`);
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `HTTP ${response.status}`);
    renderList(body);
  } catch (error) {
    listStatus.textContent = `エラー: ${error.message}`;
  }
}

closeDetail.addEventListener("click", () => {
  detailPanel.classList.add("hidden");
  openTableName = "";
  markOpenRow();
});
deleteSelectedButton.addEventListener("click", deleteSelectedReceipts);
updateDeleteSelectedButton();
configureBackLink();
loadReceipts();
