// レシート解析システム 開発文書 共通スクリプト
// 本文はHTMLだけで読めるようにし、ここではコード例のコピー機能だけを追加する。
(() => {
  "use strict";
  document.querySelectorAll("pre").forEach((pre) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "copy-button";
    button.textContent = "コピー";
    button.addEventListener("click", async () => {
      const code = pre.querySelector("code") || pre;
      try {
        await navigator.clipboard.writeText(code.innerText);
        button.textContent = "コピー済み";
      } catch (_) {
        button.textContent = "コピーできません";
      }
      setTimeout(() => { button.textContent = "コピー"; }, 1500);
    });
    pre.append(button);
  });
})();
