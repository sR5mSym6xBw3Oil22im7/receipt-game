const form = document.getElementById("login-form");

// 応答に含まれる移動先は、同じオリジンの /admin/ 配下だけを許可する（別サイトや javascript: のURLへ移動させない）
function safeAdminDestination(redirect) {
  try {
    const url = new URL(redirect, window.location.origin);
    if (url.origin === window.location.origin && url.pathname.startsWith("/admin/")) return url.pathname + url.search;
  } catch {
    // 不正なURLは既定の移動先にする
  }
  return "/admin/menu.html";
}
const status = document.getElementById("login-status");

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  status.textContent = "";
  const username = form.elements.username.value;
  const password = form.elements.password.value;
  if (!username.trim() || !password) {
    status.textContent = "ユーザーIDとパスワードを入力してください。";
    return;
  }
  if (username.length > 64 || password.length > 128) {
    status.textContent = "ユーザーIDは64文字、パスワードは128文字以内で入力してください。";
    return;
  }
  try {
    const response = await adminFetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password })
    });
    if (!response.ok) {
      if (response.status === 401) {
        status.textContent = "ユーザーIDまたはパスワードが正しくありません。";
      } else if (response.status === 400) {
        status.textContent = "入力内容を確認してください。";
      } else if (response.status === 429) {
        status.textContent = "ログインの失敗が続いたため、しばらくしてから再度お試しください。";
      } else {
        status.textContent = `ログインできませんでした (HTTP ${response.status})`;
      }
      return;
    }
    const result = await response.json();
    window.location.assign(safeAdminDestination(result.redirect));
  } catch {
    status.textContent = "Backendに接続できませんでした。";
  }
});
