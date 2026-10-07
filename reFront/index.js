const uploadLink = document.querySelector(".admin-upload-link");
if (uploadLink && window.APP_CONFIG?.ADMIN_BASE_URL) {
  uploadLink.href = `${window.APP_CONFIG.ADMIN_BASE_URL}/login.html?returnTo=upload`;
}

const adminLink = document.getElementById("select-link");
if (adminLink && window.APP_CONFIG?.ADMIN_BASE_URL) {
  adminLink.href = `${window.APP_CONFIG.ADMIN_BASE_URL}/login.html`;
}
