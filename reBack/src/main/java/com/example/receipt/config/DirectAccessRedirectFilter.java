package com.example.receipt.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * /admin/ 以外のページへの直接アクセス（アドレスバー入力・ブックマーク・メニュー以外のサイトのリンク）を、
 * フロントエンドのメニュー（reFront/index.html）へ302で転送する。
 * <p>
 * 判定はブラウザーが付ける Sec-Fetch-Mode / Sec-Fetch-Site で行う。fetch によるAPI呼び出しや、
 * CSS・スクリプト・画像の読み込みはページ移動ではないので対象外。
 * 未ログインで /admin/ を開いたときのログイン画面への移動より先に判定しないよう、Spring Security より前に置く。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DirectAccessRedirectFilter extends OncePerRequestFilter {
    private final String menuUrl;
    private final String menuOrigin;

    public DirectAccessRedirectFilter(@Value("${app.frontend.menu-url}") String menuUrl) {
        this.menuUrl = menuUrl;
        this.menuOrigin = originOf(menuUrl);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isNavigation(request) && !isAdminPath(request) && !cameFromAllowedPage(request)) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", menuUrl);
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        chain.doFilter(request, response);
    }

    /** ページ移動の要求か。Sec-Fetch-Mode を送らない古いブラウザーは Accept で判断する。 */
    private static boolean isNavigation(HttpServletRequest request) {
        String mode = request.getHeader("Sec-Fetch-Mode");
        if (mode != null) return mode.equals("navigate");
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    /** /admin/ は現状のまま（未ログインならログイン画面へ）にする。パスは正規化・デコード後の値で判定する。 */
    private static boolean isAdminPath(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return path.equals("/admin") || path.startsWith("/admin/");
    }

    /**
     * 同じサイトのページ、またはメニューのサイトからのリンクか。
     * GitHub Pages からの Referer はオリジンだけなので、オリジンで比べる。再読み込みでも Referer は引き継がれる。
     */
    private boolean cameFromAllowedPage(HttpServletRequest request) {
        if ("same-origin".equals(request.getHeader("Sec-Fetch-Site"))) return true;
        String referrerOrigin = originOf(request.getHeader("Referer"));
        return referrerOrigin != null && referrerOrigin.equals(menuOrigin);
    }

    private static String originOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
