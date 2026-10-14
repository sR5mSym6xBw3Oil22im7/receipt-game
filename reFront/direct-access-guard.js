// メニュー（reFront/index.html）以外のページへ直接アクセスされたら、メニューへ移動させる。
// 「直接アクセス」は、アドレスバーへの入力・ブックマーク・外部サイトのリンクなど、
// 同じサイトの reFront/ 配下のページからのリンクでない開き方を指す。
// 各ページの <head> の先頭で読み込み、描画が始まる前に移動する。
(function () {
  var script = document.currentScript;
  if (!script || !script.src) return;
  // このスクリプトは reFront/ 直下に置くので、同じ場所の index.html がメニュー
  var menu = new URL("./index.html", script.src);
  var menuDir = new URL("./", script.src);
  var here = new URL(window.location.href);

  // メニューそのもの（?theme= などの指定はそのまま残す）
  if (here.pathname === menu.pathname) return;

  // reFront/ のように index.html を省いたURLもメニューへ正規化する
  if (here.pathname === menuDir.pathname) {
    window.location.replace(menu.pathname + here.search + here.hash);
    return;
  }

  var cameFromSite = false;
  try {
    var referrer = document.referrer ? new URL(document.referrer) : null;
    cameFromSite = !!referrer && referrer.origin === here.origin && referrer.pathname.indexOf(menuDir.pathname) === 0;
  } catch (e) {
    cameFromSite = false;
  }
  if (!cameFromSite) window.location.replace(menu.href);
})();
