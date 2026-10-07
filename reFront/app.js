const form = document.getElementById("receipt-form");
const fileInputs = [...document.querySelectorAll('input[name="file"]')];
const apiKeyInput = document.getElementById("gemini-api-key");
const clearApiKeyButton = document.getElementById("clear-api-key");
const submitButton = document.getElementById("submit-button");
const saveButton = document.getElementById("save-button");
const statusElement = document.getElementById("status");
const resultCard = document.getElementById("result-card");
const receiptResultsElement = document.getElementById("receipt-results");
let analyzedReceipts = [];
let analysisReady = false;
let busy = false;
const stepItems = [...document.querySelectorAll("#steps li")];
const dropZone = document.getElementById("drop-zone");

function setStep(index) {
  stepItems.forEach((item, i) => {
    item.classList.toggle("is-done", i < index);
    item.classList.toggle("is-active", i === index);
  });
}

const API_BASE_URL = window.APP_CONFIG?.API_BASE_URL ?? "http://localhost:8081";
const ANALYZE_REQUEST_TIMEOUT_MS = 210000;
const ANALYSIS_WAIT_MESSAGE_INTERVAL_MS = 15000;
const SAVE_REQUEST_TIMEOUT_MS = 30000;
const API_KEY_RETRY_CODES = new Set([
  "GEMINI_QUOTA_EXCEEDED",
  "GEMINI_API_KEY_REJECTED",
  "GEMINI_API_KEY_MISSING",
  "INVALID_GEMINI_API_KEY"
]);

function responseErrorMessage(response, body) {
  if (response.status === 401) return "ログインの有効期限が切れました。管理画面に再度ログインしてください。";
  if (response.status === 403) return "この操作を行う権限がないか、画面の有効期限が切れています。ページを再読み込みしてください。";
  return body.message || `HTTP ${response.status}`;
}

function hasPendingReceipts() {
  return analysisReady && analyzedReceipts.some((receipt) => !receipt.stored);
}

function updateSaveButton() {
  const hasPending = hasPendingReceipts();
  saveButton.classList.toggle("hidden", !hasPending);
  saveButton.disabled = busy || !hasPending;
}

function invalidateAnalysis() {
  analyzedReceipts = [];
  analysisReady = false;
  receiptResultsElement.replaceChildren();
  resultCard.classList.add("hidden");
  statusElement.textContent = "";
  setStep(0);
  updateSaveButton();
}

function resetUploadPagePreservingApiKey() {
  const geminiApiKey = apiKeyInput.value;

  fileInputs.forEach((input, index) => {
    input.value = "";
    const nameElement = document.getElementById(`receipt-file-name-${index + 1}`);
    nameElement.classList.remove("error-message");
    nameElement.classList.add("is-empty");
    nameElement.textContent = "未選択";
  });

  analyzedReceipts = [];
  analysisReady = false;
  receiptResultsElement.replaceChildren();
  resultCard.classList.add("hidden");
  statusElement.classList.remove("error-message");
  statusElement.textContent = "";
  apiKeyInput.value = geminiApiKey;
  busy = false;
  submitButton.disabled = false;
  submitButton.textContent = "解析";
  saveButton.disabled = true;
  saveButton.classList.add("hidden");
  saveButton.textContent = "PostgreSQLへ保存";
  setStep(0);
}

fileInputs.forEach((input, index) => {
  input.addEventListener("change", () => {
    invalidateAnalysis();

    const selectedFile = input.files?.[0];
    const nameElement = document.getElementById(`receipt-file-name-${index + 1}`);
    if (!selectedFile) {
      nameElement.classList.remove("error-message");
      nameElement.classList.add("is-empty");
      nameElement.textContent = "未選択";
      return;
    }

    nameElement.classList.remove("error-message");
    nameElement.classList.remove("is-empty");
    nameElement.textContent = selectedFile.name;
  });
});

// ドロップされたファイルを選択欄へ渡し、通常の選択と同じ処理を行う。
["dragenter", "dragover"].forEach((type) => dropZone.addEventListener(type, (event) => {
  event.preventDefault();
  dropZone.classList.add("is-over");
}));
["dragleave", "drop"].forEach((type) => dropZone.addEventListener(type, (event) => {
  event.preventDefault();
  dropZone.classList.remove("is-over");
}));
dropZone.addEventListener("drop", (event) => {
  const droppedFiles = event.dataTransfer?.files;
  if (busy || !droppedFiles?.length) return;
  const transfer = new DataTransfer();
  transfer.items.add(droppedFiles[0]);
  fileInputs[0].files = transfer.files;
  fileInputs[0].dispatchEvent(new Event("change"));
});

function setBusy(mode) {
  busy = Boolean(mode);
  submitButton.disabled = busy;
  submitButton.textContent = mode === "analyze" ? "解析中..." : "解析";
  submitButton.classList.toggle("is-busy", mode === "analyze");
  saveButton.classList.toggle("is-busy", mode === "save");
  saveButton.textContent = mode === "save" ? "保存中..." : "PostgreSQLへ保存";
  updateSaveButton();
}

function showApiKeyRetry(errorCode, fallbackMessage) {
  apiKeyInput.focus();
  apiKeyInput.select();

  if (errorCode === "GEMINI_QUOTA_EXCEEDED") {
    statusElement.textContent =
      "Gemini APIの利用上限に達しました。次のAPIキーへ入れ替え、同じ画像のまま再度解析してください。";
    return;
  }

  if (errorCode === "GEMINI_API_KEY_REJECTED") {
    statusElement.textContent =
      "Gemini APIキーが利用できません。別のAPIキーへ入れ替え、同じ画像のまま再度解析してください。";
    return;
  }

  if (errorCode === "GEMINI_API_KEY_MISSING" || errorCode === "INVALID_GEMINI_API_KEY") {
    statusElement.textContent =
      "Gemini APIキーを確認して再度解析してください。";
    return;
  }

  statusElement.textContent = `エラー: ${fallbackMessage}`;
}

function startAnalysisWaitMessage(fileNumber, totalFiles) {
  let elapsedSeconds = 0;
  return setInterval(() => {
    elapsedSeconds += ANALYSIS_WAIT_MESSAGE_INTERVAL_MS / 1000;
    statusElement.textContent =
      `画像${fileNumber}/${totalFiles}を解析中です（${elapsedSeconds}秒経過）。Geminiの応答を待っています。ブラウザを閉じずにお待ちください。`;
  }, ANALYSIS_WAIT_MESSAGE_INTERVAL_MS);
}

async function analyzeReceipt(formData, fileNumber) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), ANALYZE_REQUEST_TIMEOUT_MS);

  try {
    const response = await adminFetch(`${API_BASE_URL}/api/receipts/analyze`, {
      method: "POST",
      body: formData,
      signal: controller.signal
    });
    const body = await response.json().catch((error) => {
      if (error?.name === "AbortError") throw error;
      return {};
    });
    return { response, body };
  } catch (error) {
    if (error?.name === "AbortError") {
      const timeoutError = new Error(
        "バックエンドサーバーから応答がありません。通信状態またはRenderの稼働状態を確認して、再度解析してください。"
      );
      timeoutError.code = "BACKEND_TIMEOUT";
      timeoutError.fileNumber = fileNumber;
      throw timeoutError;
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

function isZipFile(file) {
  return file.name.toLowerCase().endsWith(".zip") || file.type === "application/zip" || file.type === "application/x-zip-compressed";
}

function isJpeg(bytes) {
  return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

function isPng(bytes) {
  return bytes.length >= 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((value, index) => bytes[index] === value);
}

function readZipString(bytes, start, length) {
  return new TextDecoder("utf-8", { fatal: false }).decode(bytes.slice(start, start + length));
}

function isZipDirectory(name, versionMadeBy, externalAttributes) {
  if (name.endsWith("/")) return true;

  // ZIP archives created on Windows store the directory bit in the low byte.
  const hostSystem = versionMadeBy >>> 8;
  if (hostSystem === 0) return (externalAttributes & 0x10) !== 0;

  // ZIP archives created on Unix store the file type in the high mode bits.
  return ((externalAttributes >>> 16) & 0xf000) === 0x4000;
}

async function inflateRaw(bytes) {
  if (typeof DecompressionStream !== "function") {
    throw new Error("このブラウザはZIP解凍に対応していません。最新のブラウザで再実行してください。");
  }
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

async function unzipReceiptImages(zipFile) {
  const archive = new Uint8Array(await zipFile.arrayBuffer());
  const minimumEndRecord = 22;
  const searchStart = Math.max(0, archive.length - 0xffff - minimumEndRecord);
  let endOffset = -1;
  for (let offset = archive.length - minimumEndRecord; offset >= searchStart; offset--) {
    if (new DataView(archive.buffer, archive.byteOffset, archive.byteLength).getUint32(offset, true) === 0x06054b50) {
      endOffset = offset;
      break;
    }
  }
  if (endOffset < 0) throw new Error("ZIPファイルを読み込めません。ZIPが壊れている可能性があります。");

  const view = new DataView(archive.buffer, archive.byteOffset, archive.byteLength);
  const entryCount = view.getUint16(endOffset + 10, true);
  const centralSize = view.getUint32(endOffset + 12, true);
  const centralOffset = view.getUint32(endOffset + 16, true);
  if (centralOffset + centralSize > archive.length) throw new Error("ZIPファイルの構造が不正です。");

  const files = [];
  let cursor = centralOffset;
  for (let index = 0; index < entryCount; index++) {
    if (cursor + 46 > archive.length || view.getUint32(cursor, true) !== 0x02014b50) {
      throw new Error("ZIPファイルの中身を読み込めません。");
    }
    const flags = view.getUint16(cursor + 8, true);
    const method = view.getUint16(cursor + 10, true);
    const compressedSize = view.getUint32(cursor + 20, true);
    const uncompressedSize = view.getUint32(cursor + 24, true);
    const nameLength = view.getUint16(cursor + 28, true);
    const extraLength = view.getUint16(cursor + 30, true);
    const commentLength = view.getUint16(cursor + 32, true);
    const localOffset = view.getUint32(cursor + 42, true);
    const versionMadeBy = view.getUint16(cursor + 6, true);
    const externalAttributes = view.getUint32(cursor + 38, true);
    const name = readZipString(archive, cursor + 46, nameLength);
    cursor += 46 + nameLength + extraLength + commentLength;

    if (isZipDirectory(name, versionMadeBy, externalAttributes)) continue;
    if ((flags & 1) !== 0) throw new Error(`ZIP内のファイル「${name}」は暗号化されています。処理を中断しました。`);
    if (!/\.(jpe?g|png)$/i.test(name)) {
      throw new Error(`ZIP内にJPEG/PNG以外のファイル「${name}」があるため、処理を中断しました。`);
    }
    if (localOffset + 30 > archive.length || view.getUint32(localOffset, true) !== 0x04034b50) {
      throw new Error(`ZIP内のファイル「${name}」を読み込めません。`);
    }
    const localNameLength = view.getUint16(localOffset + 26, true);
    const localExtraLength = view.getUint16(localOffset + 28, true);
    const dataStart = localOffset + 30 + localNameLength + localExtraLength;
    const dataEnd = dataStart + compressedSize;
    if (dataEnd > archive.length) throw new Error(`ZIP内のファイル「${name}」が壊れています。`);
    const compressed = archive.slice(dataStart, dataEnd);
    let bytes;
    if (method === 0) bytes = compressed;
    else if (method === 8) bytes = await inflateRaw(compressed);
    else throw new Error(`ZIP内のファイル「${name}」は未対応の圧縮方式です。処理を中断しました。`);
    if (bytes.length !== uncompressedSize || (!isJpeg(bytes) && !isPng(bytes))) {
      throw new Error(`ZIP内のファイル「${name}」がJPEG/PNG画像ではないため、処理を中断しました。`);
    }
    const type = isPng(bytes) ? "image/png" : "image/jpeg";
    files.push(new File([bytes], name.split("/").pop(), { type }));
  }
  if (!files.length) throw new Error("ZIPファイルにレシート画像がありません。");
  return files;
}

async function expandSelectedFile(file) {
  if (isZipFile(file)) return unzipReceiptImages(file);
  const bytes = new Uint8Array(await file.slice(0, 8).arrayBuffer());
  if (!isJpeg(bytes) && !isPng(bytes)) throw new Error("JPEGまたはPNG画像、またはZIPファイルを選択してください。");
  return [file];
}

async function fetchSaveApi(url, options) {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), SAVE_REQUEST_TIMEOUT_MS);

  try {
    return await adminFetch(url, { ...options, signal: controller.signal });
  } catch (error) {
    if (error?.name === "AbortError") {
      const timeoutError = new Error(
        "バックエンドサーバーから応答がありません。通信状態を確認して、再度「PostgreSQLへ保存」を押してください。"
      );
      timeoutError.code = "BACKEND_TIMEOUT";
      throw timeoutError;
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
}

async function readJsonResponse(response) {
  return response.json().catch(() => ({}));
}

async function saveReceipt(lines, sha256, structuredData) {
  const response = await fetchSaveApi(`${API_BASE_URL}/api/receipts/save`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json; charset=UTF-8",
    },
    body: JSON.stringify({ lines, sha256, structuredData })
  });
  const body = await readJsonResponse(response);
  if (!response.ok) {
    const error = new Error(responseErrorMessage(response, body));
    error.code = body.code || "";
    error.httpStatus = response.status;
    throw error;
  }
  return body;
}

function renderReceiptResults(receipts) {
  receiptResultsElement.replaceChildren();
  receipts.forEach((receipt, index) => {
    const result = document.createElement("article");
    result.className = receipt.stored ? "receipt-paper is-saved" : "receipt-paper";
    const heading = document.createElement("h3");
    heading.textContent = `レシート画像${receipt.fileNumber ?? index + 1}`;
    const text = document.createElement("pre");
    text.textContent = receipt.lines.join("\n");
    result.append(heading, text);
    receiptResultsElement.append(result);
  });
}

function buildSaveCompletionMessage(receipts) {
  const storedNumbers = receipts
    .filter((receipt) => receipt.stored)
    .map((receipt) => receipt.fileNumber);

  if (storedNumbers.length) {
    return `画像${storedNumbers.join("、")}をPostgreSQLへ保存しました。`;
  }

  return "PostgreSQLへ保存する対象がありません。";
}

clearApiKeyButton.addEventListener("click", () => {
  apiKeyInput.value = "";
  apiKeyInput.focus();
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  resultCard.classList.add("hidden");
  analyzedReceipts = [];
  analysisReady = false;
  statusElement.classList.remove("error-message");
  statusElement.textContent = "";
  updateSaveButton();

  const geminiApiKey = apiKeyInput.value.trim();
  if (!geminiApiKey) {
    statusElement.textContent = "Gemini APIキーを入力してください。";
    apiKeyInput.focus();
    return;
  }

  const selectedInput = fileInputs
    .map((input, index) => ({ file: input.files?.[0], fileNumber: index + 1 }))
    .filter((entry) => Boolean(entry.file));
  if (!selectedInput.length) {
    statusElement.textContent = "画像ファイルを1枚以上選択してください。";
    return;
  }

  setBusy("analyze");
  setStep(1);
  statusElement.textContent = "";

  try {
    const expandedFiles = await expandSelectedFile(selectedInput[0].file);
    const selectedFiles = expandedFiles.map((file, index) => ({ file, fileNumber: index + 1 }));
    for (const { file, fileNumber } of selectedFiles) {
      statusElement.textContent = `画像${fileNumber}/${selectedFiles.length}を解析中です。`;
      const formData = new FormData();
      formData.append("file", file);
      formData.append("geminiApiKey", geminiApiKey);

      const waitMessageTimer = startAnalysisWaitMessage(fileNumber, selectedFiles.length);
      let analyzeResponse;
      let body;
      try {
        ({ response: analyzeResponse, body } = await analyzeReceipt(formData, fileNumber));
      } finally {
        clearInterval(waitMessageTimer);
      }
      if (!analyzeResponse.ok) {
        const error = new Error(responseErrorMessage(analyzeResponse, body));
        error.code = body.code || "";
        error.httpStatus = analyzeResponse.status;
        error.fileNumber = fileNumber;
        throw error;
      }

      analyzedReceipts.push({
        fileNumber,
        lines: Array.isArray(body.lines) ? body.lines : [],
        sha256: body.sha256 || "",
        structuredData: body.structuredData || null,
        tableName: "",
        stored: false
      });
      renderReceiptResults(analyzedReceipts);
      resultCard.classList.remove("hidden");
    }

    analysisReady = true;
    setStep(2);
    statusElement.textContent = `${analyzedReceipts.length}枚の解析が完了しました。`;
  } catch (error) {
    analysisReady = false;
    setStep(0);
    if (API_KEY_RETRY_CODES.has(error.code)) {
      showApiKeyRetry(error.code, error.message);
    } else {
      const fileMessage = error.fileNumber ? `（画像${error.fileNumber}）` : "";
      statusElement.textContent = `解析エラー${fileMessage}: ${error.message}`;
    }

    if (analyzedReceipts.length) {
      renderReceiptResults(analyzedReceipts);
      resultCard.classList.remove("hidden");
    }
  } finally {
    setBusy(null);
  }
});

saveButton.addEventListener("click", async () => {
  if (busy) return;
  if (!hasPendingReceipts()) {
    statusElement.textContent = "PostgreSQLへ保存する未保存の解析結果がありません。先に解析してください。";
    updateSaveButton();
    return;
  }

  statusElement.classList.remove("error-message");
  setBusy("save");
  setStep(3);
  statusElement.textContent = "";

  let saveCompleted = false;
  try {
    for (const receipt of analyzedReceipts) {
      if (receipt.stored) continue;

      try {
        statusElement.textContent = `画像${receipt.fileNumber}をPostgreSQLへ保存中です。`;
        const saved = await saveReceipt(receipt.lines, receipt.sha256, receipt.structuredData);
        receipt.tableName = saved.tableName || "";
        receipt.stored = true;
      } catch (error) {
        error.fileNumber = receipt.fileNumber;
        throw error;
      }

      renderReceiptResults(analyzedReceipts);
    }

    statusElement.textContent = buildSaveCompletionMessage(analyzedReceipts);
    saveCompleted = true;
  } catch (error) {
    const fileMessage = error.fileNumber ? `（画像${error.fileNumber}）` : "";
    statusElement.textContent = `保存エラー${fileMessage}: ${error.message}`;
  } finally {
    if (saveCompleted) {
      // 保存成功後は初期状態へ戻し、次の解析を開始できるようにする。
      resetUploadPagePreservingApiKey();
    } else {
      // 保存失敗時は解析結果と保存ボタンを残し、再試行できるようにする。
      setStep(2);
      setBusy(null);
    }
  }
});

updateSaveButton();
