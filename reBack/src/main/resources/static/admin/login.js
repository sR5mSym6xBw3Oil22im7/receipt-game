const form = document.getElementById("login-form");
const status = document.getElementById("login-status");

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  status.textContent = "";
  try {
    const response = await adminFetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: form.elements.username.value, password: form.elements.password.value })
    });
    if (!response.ok) {
      if (response.status === 401) {
        status.textContent = "ユーザーIDまたはパスワードが正しくありません。";
      } else if (response.status === 429) {
        status.textContent = "ログインの失敗が続いたため、しばらくしてから再度お試しください。";
      } else {
        status.textContent = `ログインできませんでした (HTTP ${response.status})`;
      }
      return;
    }
    const result = await response.json();
    const returnTo = new URLSearchParams(window.location.search).get("returnTo");
    const destination = returnTo === "upload" ? "/admin/upload.html" : (result.redirect || "/admin/select.html");
    window.location.assign(destination);
  } catch {
    status.textContent = "Backendに接続できませんでした。";
  }
});
