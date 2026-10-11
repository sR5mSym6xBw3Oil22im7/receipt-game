package poc;

import com.example.receipt.dto.ReceiptItemData;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.dto.ReceiptText;
import com.example.receipt.monster.MonsterAi;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.MonsterParameters;
import com.example.receipt.repository.ReceiptTableRepository;
import com.example.receipt.service.LoginAttemptLimiter;
import com.example.receipt.service.ReceiptAnalyzer;
import com.example.receipt.service.ReceiptService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 実際にTomcatを起動して HTTP で確認するPoC。DBはH2(PostgreSQLモード)。Geminiは呼ばない。
 * 本番相当にするため Cookie の Secure を有効にし、MONSTER_TRUST_FORWARDED_FOR=true（render.yml と同じ）にしている。
 * 結果は "POC|ID|..." で標準出力に出す。
 */
@SpringBootTest(classes = com.example.receipt.ReceiptApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:poc-http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "server.servlet.session.cookie.secure=true",
        "app.auth.cookie-secure=true",
        "app.monster.trust-forwarded-for=true",
        "app.monster.ai-illustration-enabled=false"})
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(HttpPocTest.Cfg.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpPocTest {
    @LocalServerPort int port;
    @Autowired ReceiptService receiptService;
    @Autowired ReceiptTableRepository receiptRepository;
    @Autowired LoginAttemptLimiter limiter;
    @Autowired CountingAnalyzer analyzer;

    static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final AtomicInteger N = new AtomicInteger();

    static void out(String id, String msg) { System.out.println("POC|" + id + "|" + msg); }

    // ---------- 小さな HTTP ヘルパー（Cookieは手動管理） ----------
    record Res(int status, String body, java.net.http.HttpHeaders headers) {
        JsonObject json() { return JsonParser.parseString(body).getAsJsonObject(); }
        String code() { try { return json().get("code").getAsString(); } catch (Exception e) { return "-"; } }
    }

    static class Jar {
        final Map<String, String> cookies = new LinkedHashMap<>();
        void absorb(Res r) {
            for (String sc : r.headers().allValues("set-cookie")) {
                String[] kv = sc.split(";", 2)[0].split("=", 2);
                if (kv.length == 2) { if (kv[1].isEmpty() || sc.toLowerCase().contains("max-age=0")) cookies.remove(kv[0]); else cookies.put(kv[0], kv[1]); }
            }
        }
        String header() { StringBuilder sb = new StringBuilder(); cookies.forEach((k, v) -> sb.append(sb.length() > 0 ? "; " : "").append(k).append('=').append(v)); return sb.toString(); }
    }

    Res send(String method, String path, Jar jar, Map<String, String> headers, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(java.time.Duration.ofSeconds(120));
        if (contentType != null) b.header("Content-Type", contentType);
        if (jar != null && !jar.cookies.isEmpty()) b.header("Cookie", jar.header());
        if (headers != null) headers.forEach(b::header);
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Res res = new Res(r.statusCode(), r.body(), r.headers());
        if (jar != null) jar.absorb(res);
        return res;
    }
    Res get(String path, Jar jar, Map<String, String> h) throws Exception { return send("GET", path, jar, h, null, null); }
    Res postJson(String path, Jar jar, Map<String, String> h, String json) throws Exception {
        return send("POST", path, jar, h, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    Map<String, String> csrf(Jar jar) throws Exception {
        Res r = get("/api/auth/csrf", jar, null);
        return Map.of("X-XSRF-TOKEN", r.json().get("token").getAsString());
    }

    Jar loginAdmin() throws Exception {
        Jar jar = new Jar();
        Res r = postJson("/api/auth/login", jar, csrf(jar), "{\"username\":\"poc-admin2\",\"password\":\"password\"}");
        if (r.status() != 200) throw new AssertionError("login failed " + r.status() + r.body());
        return jar;
    }

    static byte[] multipart(String boundary, String filename, String contentType, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        o.writeBytes(head); o.writeBytes(content); o.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return o.toByteArray();
    }

    static String sha() { return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64); }

    // ---------- 1. エンドポイントの認可マトリクス・ヘッダー・Cookie ----------
    @Test @Order(1)
    void endpointMatrixAndHeaders() throws Exception {
        String[][] probes = {
                {"GET", "/api/health"}, {"GET", "/api/auth/session"}, {"GET", "/api/auth/csrf"},
                {"GET", "/api/receipts"}, {"POST", "/api/receipts/save"}, {"POST", "/api/receipts/analyze"},
                {"GET", "/api/receipts/receipt_00000000000000000000000000000000"}, {"DELETE", "/api/receipts/receipt_00000000000000000000000000000000"},
                {"POST", "/api/auth/logout"}, {"GET", "/api/unknown"}, {"GET", "/actuator/health"}, {"GET", "/error"},
                {"GET", "/admin/login.html"}, {"GET", "/admin/menu.html"}, {"GET", "/admin/upload.html"}, {"GET", "/admin/app.js"},
                {"GET", "/admin/config.js"}, {"GET", "/admin/admin-api.js"}, {"GET", "/admin/select.js"}, {"GET", "/admin/styles.css"},
                {"GET", "/game/index.html"}, {"GET", "/game/game.js"}, {"GET", "/demo/index.html"}, {"GET", "/theme/theme.js"},
                {"GET", "/index.html"}, {"GET", "/"}, {"GET", "/application.yml"}, {"GET", "/application-local.yml"}, {"GET", "/.env"},
                // パス細工
                {"GET", "/API/receipts"}, {"GET", "/api/receipts/"}, {"GET", "/api//receipts"}, {"GET", "/api/receipts;x=1"},
                {"GET", "/api/receipts/%2e%2e/health"}, {"GET", "/api/monster/rooms/../../receipts"}, {"GET", "/admin/../api/receipts"},
                {"GET", "/api/receipts.json"}, {"GET", "/admin;jsessionid=x/menu.html"}, {"GET", "/%61pi/receipts"},
        };
        for (String[] p : probes) {
            try {
                Res r = send(p[0], p[1], null, null, null, null);
                out("MATRIX", String.format("%-6s %-62s -> %d", p[0], p[1], r.status()));
            } catch (Exception e) {
                out("MATRIX", String.format("%-6s %-62s -> EXC %s", p[0], p[1], e.getClass().getSimpleName()));
            }
        }
        Res game = get("/game/index.html", null, null);
        out("HEADERS", "/game/index.html " + new TreeMap<>(game.headers().map()));
        Res csrf = get("/api/auth/csrf", null, null);
        out("HEADERS", "/api/auth/csrf " + new TreeMap<>(csrf.headers().map()));
        Res api = get("/api/monster/rooms/ABCDEF", null, null);
        out("HEADERS", "/api/monster/rooms/ABCDEF " + api.status() + " " + new TreeMap<>(api.headers().map()));
    }

    // ---------- 2. 認証・CSRF・セッション ----------
    @Test @Order(2)
    void authFlows() throws Exception {
        Jar anon = new Jar();
        out("CSRF", "login without token -> " + postJson("/api/auth/login", anon, null, "{\"username\":\"poc-admin\",\"password\":\"password\"}").status());
        out("CSRF", "unauthenticated POST /api/receipts/save (no token) -> " + postJson("/api/receipts/save", anon, null, "{}").status());
        out("CSRF", "unauthenticated DELETE (no token) -> " + send("DELETE", "/api/receipts/receipt_00000000000000000000000000000000", anon, null, null, null).status());
        out("CSRF", "monster create without any token/cookie -> " + postJson("/api/monster/rooms", anon, Map.of("X-Forwarded-For", "10.9.9." + N.incrementAndGet()), "").status());

        Jar jar = new Jar();
        Res tok = get("/api/auth/csrf", jar, null);
        out("COOKIE", "csrf Set-Cookie=" + tok.headers().allValues("set-cookie"));
        // セッション固定: 攻撃者が用意した JSESSIONID を送ってログインしても、ログイン後は別IDになるか
        jar.cookies.put("JSESSIONID", "ATTACKERFIXEDSESSIONID000000000000");
        Res login = postJson("/api/auth/login", jar, Map.of("X-XSRF-TOKEN", tok.json().get("token").getAsString()), "{\"username\":\"poc-admin\",\"password\":\"password\"}");
        out("FIXATION", "login=" + login.status() + " Set-Cookie=" + login.headers().allValues("set-cookie") + " jarJSESSIONID=" + jar.cookies.get("JSESSIONID"));
        Res s = get("/api/auth/session", jar, null);
        out("SESSION", "session body=" + s.body());
        // 旧(固定)IDではログインできていないこと
        Jar fixed = new Jar(); fixed.cookies.put("JSESSIONID", "ATTACKERFIXEDSESSIONID000000000000");
        out("FIXATION", "request with fixed id -> /api/receipts " + get("/api/receipts", fixed, null).status());
        // CSRF: ログイン済みで token なし / 誤 token
        out("CSRF", "admin POST /api/receipts/save no token -> " + postJson("/api/receipts/save", jar, null, "{}").status());
        out("CSRF", "admin POST /api/receipts/save wrong token -> " + postJson("/api/receipts/save", jar, Map.of("X-XSRF-TOKEN", "wrong"), "{}").status());
        // ログアウト後
        Res lo = postJson("/api/auth/logout", jar, csrf(jar), "");
        out("LOGOUT", "status=" + lo.status() + " Set-Cookie=" + lo.headers().allValues("set-cookie") + " /api/receipts after logout with old cookie -> " + get("/api/receipts", jar, null).status());
        // 保存された古いセッションIDの再利用
    }

    // ---------- 3. ログイン入力・DoS ----------
    @Test @Order(3)
    void loginInputsAndLockout() throws Exception {
        Jar jar = new Jar();
        Map<String, String> c = csrf(jar);
        String[] bodies = {"", "null", "[]", "{}", "{\"username\":null,\"password\":null}", "{\"username\":123,\"password\":true}", "{\"username\":\"poc-admin\"}",
                "not json", "{\"username\":\"poc-admin\",\"password\":\"" + "p".repeat(100_000) + "\"}"};
        for (String b : bodies) {
            Res r = postJson("/api/auth/login", jar, c, b);
            String shown = b.length() > 60 ? b.substring(0, 40) + "...(" + b.length() + "chars)" : b;
            out("LOGIN-INPUT", String.format("%-60s -> %d %s", shown, r.status(), r.status() >= 500 ? r.body() : ""));
        }
        Res noCt = send("POST", "/api/auth/login", jar, c, "{}".getBytes(), "text/plain");
        out("LOGIN-INPUT", "Content-Type text/plain -> " + noCt.status() + " " + noCt.body());

        // 巨大ユーザー名: Jackson の上限・メモリ保持
        for (int mb : new int[]{5, 25}) {
            String big = "u".repeat(mb * 1_000_000);
            long t = System.nanoTime();
            Res r = postJson("/api/auth/login", jar, c, "{\"username\":\"" + big + "\",\"password\":\"x\"}");
            out("LOGIN-HUGE", mb + "M文字のusername -> status=" + r.status() + " ms=" + (System.nanoTime() - t) / 1_000_000);
        }
        java.lang.reflect.Field f = LoginAttemptLimiter.class.getDeclaredField("states");
        f.setAccessible(true);
        Map<?, ?> states = (Map<?, ?>) f.get(limiter);
        long chars = 0; for (Object k : states.keySet()) chars += ((String) k).length();
        out("LOGIN-HUGE", "limiterが保持しているキー数=" + states.size() + " 合計文字数=" + chars);

        // ロックアウト: 攻撃者が admin 名で5回失敗させる → 正しいパスワードでも入れない
        for (int i = 0; i < 5; i++) postJson("/api/auth/login", jar, csrf(jar), "{\"username\":\"poc-admin\",\"password\":\"wrong\"}");
        Res locked = postJson("/api/auth/login", jar, csrf(jar), "{\"username\":\"poc-admin\",\"password\":\"password\"}");
        out("SEC-LOCK-DOS", "5回失敗後に正しいパスワードで -> " + locked.status());
        Res lockedNonexistent = null;
        for (int i = 0; i < 5; i++) lockedNonexistent = postJson("/api/auth/login", jar, csrf(jar), "{\"username\":\"nobody\",\"password\":\"wrong\"}");
        Res sixth = postJson("/api/auth/login", jar, csrf(jar), "{\"username\":\"nobody\",\"password\":\"wrong\"}");
        out("ENUM", "実在しないユーザー名も6回目は " + sixth.status() + " body=" + sixth.body() + " / 実在: " + locked.status() + " body=" + locked.body());

        // 応答時間の差（実在 vs 非実在、ロックされない別名で各10回）
        long tReal = 0, tFake = 0;
        for (int i = 0; i < 4; i++) {
            long a = System.nanoTime(); postJson("/api/auth/login", jar, csrf(jar), "{\"username\":\"POC-ADMIN \",\"password\":\"w" + i + "\"}"); tReal += System.nanoTime() - a;
            long b = System.nanoTime(); postJson("/api/auth/login", jar, csrf(jar), "{\"username\":\"fake" + i + "\",\"password\":\"w" + i + "\"}"); tFake += System.nanoTime() - b;
        }
        out("ENUM-TIMING", "avg ms real-name=" + tReal / 4_000_000 + " fake-name=" + tFake / 4_000_000 + " (※実在名は先にロックされるため2回目以降は429で短い点に注意)");
    }

    @Test @Order(4)
    void bcryptTruncation() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        String hash = enc.encode("a".repeat(72));
        try {
            out("BCRYPT", "72バイトのハッシュに100バイトの入力 matches=" + enc.matches("a".repeat(100), hash));
        } catch (Throwable t) {
            out("BCRYPT", "例外 " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    // ---------- 4. 管理API ----------
    @Test @Order(5)
    void adminApiInputs() throws Exception {
        Jar jar = loginAdmin();
        // パス変数
        String[] names = {"receipt_00000000000000000000000000000000", "receipt_ABCDEF00000000000000000000000000", "x", "receipt_0'; DROP TABLE x;--", "..%2f..%2fetc", "%00", "receipt_0000000000000000000000000000000%0a", new String(new char[300]).replace('\0', 'a')};
        for (String n : names) {
            try {
                Res g = get("/api/receipts/" + n, jar, null);
                Res d = send("DELETE", "/api/receipts/" + n, jar, csrf(jar), null, null);
                out("TABLENAME", String.format("%-48.48s GET=%d(%s) DELETE=%d(%s)", n, g.status(), g.code(), d.status(), d.code()));
            } catch (Exception e) { out("TABLENAME", n + " EXC " + e.getClass().getSimpleName()); }
        }

        // /save の入力パターン
        record Case(String name, String lines, String structured, boolean reserve, String shaOverride) {}
        List<Case> cases = List.of(
                new Case("lines=null", "null", "null", true, null),
                new Case("lines=[]", "[]", "null", true, null),
                new Case("lines=[null]", "[null]", "null", true, null),
                new Case("lines=['  ']", "[\"  \"]", "null", true, null),
                new Case("lines が文字列(型違い)", "\"abc\"", "null", true, null),
                new Case("lines=[1,2]", "[1,2]", "null", true, null),
                new Case("lines=[NUL含む]", "[\"a\\u0000b\"]", "null", true, null),
                new Case("lines=[制御文字/改行]", "[\"a\\r\\nb\\u0007\"]", "null", true, null),
                new Case("lines=[絵文字]", "[\"🍣🍣\"]", "null", true, null),
                new Case("lines=[英数字だけの1行 30,000文字]", "[\"" + "a".repeat(30_000) + "\"]", "null", true, null),
                new Case("structured=null", "[\"ok\"]", "null", true, null),
                new Case("structured={} (全項目null)", "[\"ok\"]", "{}", true, null),
                new Case("storeName 300文字", "[\"ok\"]", "{\"storeName\":\"" + "s".repeat(300) + "\"}", true, null),
                new Case("storeCategory 100文字", "[\"ok\"]", "{\"storeCategory\":\"" + "c".repeat(100) + "\"}", true, null),
                new Case("storeCategory=HTML", "[\"ok\"]", "{\"storeCategory\":\"<img src=x onerror=alert(1)>\"}", true, null),
                new Case("items=[null]", "[\"ok\"]", "{\"items\":[null]}", true, null),
                new Case("items name=null", "[\"ok\"]", "{\"items\":[{\"name\":null}]}", true, null),
                new Case("amount 負", "[\"ok\"]", "{\"totalAmount\":-5,\"items\":[{\"name\":\"a\",\"amount\":-9999999999}]}", true, null),
                new Case("amount=Long.MAX", "[\"ok\"]", "{\"totalAmount\":9223372036854775807,\"items\":[{\"name\":\"a\",\"amount\":9223372036854775807},{\"name\":\"b\",\"amount\":9223372036854775807}]}", true, null),
                new Case("amount が小数", "[\"ok\"]", "{\"totalAmount\":1.5}", true, null),
                new Case("amount が文字列", "[\"ok\"]", "{\"totalAmount\":\"abc\"}", true, null),
                new Case("quantity 1e30", "[\"ok\"]", "{\"items\":[{\"name\":\"a\",\"quantity\":1e30}]}", true, null),
                new Case("quantity 1E999999999", "[\"ok\"]", "{\"items\":[{\"name\":\"a\",\"quantity\":1E999999999}]}", true, null),
                new Case("purchasedAt 不正", "[\"ok\"]", "{\"purchasedAt\":\"yesterday\"}", true, null),
                new Case("purchasedAt 年99999", "[\"ok\"]", "{\"purchasedAt\":\"+99999-01-01T00:00:00\"}", true, null),
                new Case("sha256 大文字", "[\"ok\"]", "null", true, "UPPER"),
                new Case("sha256 63桁", "[\"ok\"]", "null", false, "63"),
                new Case("sha256 null", "[\"ok\"]", "null", false, "NULL"),
                new Case("sha256 未予約(解析していない)", "[\"ok\"]", "null", false, null),
                new Case("PII: 氏名+電話+カード", "[\"山田太郎様\",\"TEL 03-1234-5678\",\"4111 1111 1111 1111\"]", "null", true, null)
        );
        for (Case c : cases) {
            String s = c.shaOverride == null ? sha() : c.shaOverride;
            if ("UPPER".equals(s)) s = sha().toUpperCase();
            if ("63".equals(s)) s = sha().substring(1);
            if (c.reserve && !"NULL".equals(s)) receiptRepository.reserveImageHash(s.toLowerCase());
            String shaJson = "NULL".equals(s) ? "null" : "\"" + s + "\"";
            String body = "{\"lines\":" + c.lines + ",\"sha256\":" + shaJson + ",\"structuredData\":" + c.structured + "}";
            long t = System.nanoTime();
            Res r = postJson("/api/receipts/save", jar, csrf(jar), body);
            String detail = r.status() >= 400 ? r.code() + " " + trunc(r.body()) : "OK";
            out("SAVE", String.format("%-32s -> %d %s (%dms)", c.name, r.status(), detail, (System.nanoTime() - t) / 1_000_000));
        }
        // JSON構文エラー・Content-Type違い・巨大ボディ
        Res bad = postJson("/api/receipts/save", jar, csrf(jar), "{broken");
        out("SAVE", "壊れたJSON -> " + bad.status() + " " + trunc(bad.body()));
        Res ct = send("POST", "/api/receipts/save", jar, csrf(jar), "{}".getBytes(), "text/plain");
        out("SAVE", "Content-Type text/plain -> " + ct.status() + " " + trunc(ct.body()));
        Res empty = send("POST", "/api/receipts/save", jar, csrf(jar), new byte[0], "application/json");
        out("SAVE", "空ボディ -> " + empty.status() + " " + trunc(empty.body()));
        int lines = 200_000;
        StringBuilder sb = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < lines; i++) sb.append(i == 0 ? "" : ",").append("\"x").append(i).append('"');
        String s2 = sha(); receiptRepository.reserveImageHash(s2);
        sb.append("],\"sha256\":\"").append(s2).append("\"}");
        long t = System.nanoTime();
        Res many = postJson("/api/receipts/save", jar, csrf(jar), sb.toString());
        out("SAVE", lines + "行 -> " + many.status() + " " + trunc(many.body()) + " (" + (System.nanoTime() - t) / 1_000_000 + "ms)");

        // analyze のアップロード検証（Content-Type だけで通るか）
        analyzer.calls.set(0);
        byte[] junk = "THIS IS NOT AN IMAGE <?php echo 1; ?>".getBytes();
        Res a1 = send("POST", "/api/receipts/analyze", jar, csrf(jar), multipart("b1", "x.png", "image/png", junk), "multipart/form-data; boundary=b1");
        out("UPLOAD", "analyze: 中身が画像でない + Content-Type=image/png -> " + a1.status() + " analyzerCalls=" + analyzer.calls.get());
        Res a2 = send("POST", "/api/receipts/analyze", jar, csrf(jar), multipart("b2", "x.png", "image/gif", junk), "multipart/form-data; boundary=b2");
        out("UPLOAD", "analyze: Content-Type=image/gif -> " + a2.status() + " " + a2.code());
        Res a3 = send("POST", "/api/receipts/analyze", jar, csrf(jar), multipart("b3", "x.png", "image/png", new byte[0]), "multipart/form-data; boundary=b3");
        out("UPLOAD", "analyze: 空ファイル -> " + a3.status() + " " + a3.code());
        Map<String, String> cs = csrf(jar);
        out("UPLOAD", "analyze: 6MiB -> " + rawMultipartStatus("/api/receipts/analyze", jar.header() + "", cs.get("X-XSRF-TOKEN"), 6 * 1024 * 1024));
        Res a5 = send("POST", "/api/receipts/analyze", jar, csrf(jar), "x".getBytes(), "multipart/form-data");
        out("UPLOAD", "analyze: boundaryなし -> " + a5.status() + " " + trunc(a5.body()));
        Res a6 = send("POST", "/api/receipts/analyze", jar, csrf(jar), "x".getBytes(), "application/json");
        out("UPLOAD", "analyze: Content-Type=json -> " + a6.status() + " " + trunc(a6.body()));
    }


    /** 生ソケットで multipart を送り、最初のレスポンス行を返す（サイズ超過でサーバーが接続を閉じる場合も観察できる）。 */
    String rawMultipartStatus(String path, String cookie, String csrfToken, int size) {
        String boundary = "rawb";
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        long len = head.length + (long) size + tail.length;
        StringBuilder req = new StringBuilder("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: multipart/form-data; boundary=" + boundary + "\r\nContent-Length: " + len + "\r\n");
        if (cookie != null && !cookie.isEmpty()) req.append("Cookie: ").append(cookie).append("\r\n");
        if (csrfToken != null) req.append("X-XSRF-TOKEN: ").append(csrfToken).append("\r\n");
        req.append("\r\n");
        try (java.net.Socket sock = new java.net.Socket("localhost", port)) {
            sock.setSoTimeout(60_000);
            java.io.OutputStream os = sock.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            os.write(head);
            byte[] chunk = new byte[64 * 1024];
            long sent = 0;
            String sendNote = "";
            try {
                while (sent < size) { int n = (int) Math.min(chunk.length, size - sent); os.write(chunk, 0, n); sent += n; }
                os.write(tail); os.flush();
            } catch (java.io.IOException e) { sendNote = " (送信中に切断 sent=" + sent + ")"; }
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String line;
            try { line = br.readLine(); } catch (java.io.IOException e) { line = "(応答なし: " + e.getMessage() + ")"; }
            return line + sendNote;
        } catch (Exception e) { return "EXC " + e; }
    }

    static String trunc(String s) { return s == null ? "" : s.length() > 140 ? s.substring(0, 140) + "..." : s; }

    // ---------- 5. モンスター対戦API ----------
    @Test @Order(6)
    void monsterApi() throws Exception {
        for (int i = 0; i < 12; i++) {
            String s = sha();
            receiptRepository.reserveImageHash(s);
            receiptService.store(List.of("店" + i, "合計 " + (1000 + i)), s, new ReceiptStructuredData("店" + i, "支店" + i, "スーパー",
                    LocalDateTime.parse("2026-10-0" + (1 + i % 9) + "T1" + (i % 10) + ":2" + (i % 10) + ":00"), 1000L + i, "現金", "No" + i,
                    List.of(new ReceiptItemData("米", "食料品", BigDecimal.ONE, 800L, 800L))));
        }
        Map<String, String> ip = Map.of("X-Forwarded-For", "203.0.113." + N.incrementAndGet());
        Res c = postJson("/api/monster/rooms", null, ip, "");
        JsonObject room = c.json();
        String code = room.get("code").getAsString(), t1 = room.get("token").getAsString();
        out("MONSTER", "create -> " + c.status() + " keys=" + room.keySet() + " tokenLen=" + t1.length() + " code=" + code);
        Res j = postJson("/api/monster/rooms/" + code + "/join", null, ip, "");
        String t2 = j.json().get("token").getAsString();
        out("MONSTER", "join -> " + j.status() + " seat=" + j.json().get("seat").getAsString());
        Res j2 = postJson("/api/monster/rooms/" + code + "/join", null, ip, "");
        out("MONSTER", "3人目のjoin -> " + j2.status() + " " + j2.code());

        // 席トークンなし・誤り・別ルーム
        Res c2 = postJson("/api/monster/rooms", null, Map.of("X-Forwarded-For", "203.0.113." + N.incrementAndGet()), "");
        String codeB = c2.json().get("code").getAsString(), tB = c2.json().get("token").getAsString();
        for (String path : new String[]{"", "/field", "/battle"}) {
            Res none = get("/api/monster/rooms/" + code + path, null, null);
            Res wrong = get("/api/monster/rooms/" + code + path, null, Map.of("X-Seat-Token", "wrong"));
            Res other = get("/api/monster/rooms/" + code + path, null, Map.of("X-Seat-Token", tB));
            out("MONSTER-AUTHZ", "GET " + (path.isEmpty() ? "state" : path) + " token無=" + none.status() + " 誤り=" + wrong.status() + " 別ルームの席=" + other.status());
        }
        for (String path : new String[]{"/shuffle", "/leave"}) {
            out("MONSTER-AUTHZ", "POST " + path + " token無=" + postJson("/api/monster/rooms/" + code + path, null, null, "").status() + " 別ルームの席=" + postJson("/api/monster/rooms/" + code + path, null, Map.of("X-Seat-Token", tB), "").status());
        }
        out("MONSTER-AUTHZ", "flip token無=" + postJson("/api/monster/rooms/" + code + "/flip", null, null, "{\"index\":0}").status()
                + " select token無=" + postJson("/api/monster/rooms/" + code + "/select", null, null, "{\"cardId\":1}").status()
                + " rematch token無=" + postJson("/api/monster/rooms/" + code + "/rematch", null, null, "{\"round\":1}").status());
        // ルームの存在判定（404 vs 403）
        out("MONSTER-ORACLE", "存在するルーム(token無)=" + get("/api/monster/rooms/" + code, null, null).status() + " 存在しない=" + get("/api/monster/rooms/ZZZZZZ", null, null).status() + " 形式不正=" + get("/api/monster/rooms/abc", null, null).status());

        // 入力検証
        Map<String, String> h1 = Map.of("X-Seat-Token", t1);
        String[] flipBodies = {"{\"index\":0}", "{\"index\":-1}", "{\"index\":999}", "{\"index\":\"0\"}", "{\"index\":1.5}", "{\"index\":99999999999}", "{}", "null", "", "[0]", "{\"index\":null}"};
        for (String b : flipBodies) {
            Res r = postJson("/api/monster/rooms/" + code + "/flip", null, h1, b);
            out("MONSTER-FLIP", String.format("%-24s -> %d %s", b, r.status(), r.code()));
        }
        out("MONSTER-FLIP", "Content-Type無し -> " + send("POST", "/api/monster/rooms/" + code + "/flip", null, h1, "{\"index\":3}".getBytes(), null).status());
        Res field = get("/api/monster/rooms/" + code + "/field", null, h1);
        JsonArray rec = field.json().getAsJsonArray("receipts");
        out("MONSTER-FIELD", "P1の配布数=" + rec.size() + " handLimit=" + field.json().get("handLimit") + " 応答に sha/table 文字列を含む=" + (field.body().contains("receipt_") || field.body().toLowerCase().contains("sha")));
        out("MONSTER-FIELD", "最初のレシートの項目=" + rec.get(0).getAsJsonObject().getAsJsonObject("receipt").keySet());

        // 他人の席(P2)のカードIDでselect
        Res f2 = get("/api/monster/rooms/" + code + "/field", null, Map.of("X-Seat-Token", t2));
        // P2 の field の cardId は応答に含まれないので DB から取得できないが、flipして手札のIDを得る
        Res fl2 = postJson("/api/monster/rooms/" + code + "/flip", null, Map.of("X-Seat-Token", t2), "{\"index\":0}");
        long p2Card = fl2.json().getAsJsonObject("card").get("id").getAsLong();
        Res sel = postJson("/api/monster/rooms/" + code + "/select", null, h1, "{\"cardId\":" + p2Card + "}");
        out("MONSTER-SELECT", "P1がP2の手札カードIDをselect -> " + sel.status() + " " + sel.code());
        Res sel2 = postJson("/api/monster/rooms/" + code + "/select", null, h1, "{\"cardId\":999999}");
        out("MONSTER-SELECT", "存在しないカードID -> " + sel2.status() + " " + sel2.code());

        // 同時flip: 1つの席から9枚を同時に → 手札上限3枚を超えないか
        ExecutorService ex = Executors.newFixedThreadPool(12);
        List<Future<Integer>> fs = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 1; i < 6; i++) {
            final int idx = i;
            fs.add(ex.submit(() -> { start.await(); return postJson("/api/monster/rooms/" + code + "/flip", null, h1, "{\"index\":" + idx + "}").status(); }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : fs) statuses.add(f.get());
        Res after = get("/api/monster/rooms/" + code + "/field", null, h1);
        out("MONSTER-RACE", "同時flip(5本)の結果=" + statuses + " 最終手札枚数=" + after.json().getAsJsonArray("hand").size());

        // 同時select(両席)→ 対戦は1回だけ作られるか
        long p1Hand = after.json().getAsJsonArray("hand").get(0).getAsJsonObject().get("id").getAsLong();
        long p2Hand = fl2.json().getAsJsonObject("card").get("id").getAsLong();
        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> s1 = ex.submit(() -> { go.await(); return postJson("/api/monster/rooms/" + code + "/select", null, h1, "{\"cardId\":" + p1Hand + "}").status(); });
        Future<Integer> s2 = ex.submit(() -> { go.await(); return postJson("/api/monster/rooms/" + code + "/select", null, Map.of("X-Seat-Token", t2), "{\"cardId\":" + p2Hand + "}").status(); });
        go.countDown();
        out("MONSTER-RACE", "同時select -> " + s1.get() + "," + s2.get());
        Res battle = get("/api/monster/rooms/" + code + "/battle", null, h1);
        out("MONSTER-BATTLE", "battle -> " + battle.status() + " keys=" + (battle.status() == 200 ? battle.json().keySet() : "-"));
        if (battle.status() == 200) {
            JsonObject rcp = battle.json().getAsJsonObject("receipts").getAsJsonObject("P2");
            out("MONSTER-BATTLE", "相手に見えるレシート項目=" + rcp.keySet() + " receiptNumber=" + rcp.get("receiptNumber") + " branchName=" + rcp.get("branchName"));
        }
        // 終了後の操作
        out("MONSTER-STATE", "対戦後の flip=" + postJson("/api/monster/rooms/" + code + "/flip", null, h1, "{\"index\":0}").status() + " select=" + postJson("/api/monster/rooms/" + code + "/select", null, h1, "{\"cardId\":" + p1Hand + "}").status() + " shuffle=" + postJson("/api/monster/rooms/" + code + "/shuffle", null, h1, "").status());
        Res rm = postJson("/api/monster/rooms/" + code + "/rematch", null, h1, "{\"round\":" + battle.json().get("round").getAsInt() + "}");
        out("MONSTER-STATE", "rematch(現在round) -> " + rm.status() + " status=" + (rm.status() == 200 ? rm.json().get("status") : rm.code()));
        Res rm2 = postJson("/api/monster/rooms/" + code + "/rematch", null, h1, "{\"round\":0}");
        out("MONSTER-STATE", "rematch(古いround) -> " + rm2.status());
        out("MONSTER-STATE", "leave -> " + postJson("/api/monster/rooms/" + code + "/leave", null, h1, "").status() + " leave後のstate=" + get("/api/monster/rooms/" + code, null, h1).status());

        // image-card: 認可後の検証
        Res ic = send("POST", "/api/monster/rooms/" + codeB + "/image-card", null, Map.of("X-Seat-Token", tB, "Content-Type", "multipart/form-data; boundary=zz"), multipart("zz", "a.png", "image/png", "not an image".getBytes()), "multipart/form-data; boundary=zz");
        out("MONSTER-IMAGE", "画像でないファイル(P1のみ・SELECTINGでない) -> " + ic.status() + " " + ic.code());
        out("MONSTER-IMAGE", "token誤り + 6MiB -> " + rawMultipartStatus("/api/monster/rooms/" + codeB + "/image-card", "", null, 6 * 1024 * 1024));
        long t = System.nanoTime();
        out("MONSTER-IMAGE", "存在しないルーム + 4.9MB(認証前に全量受信される) -> " + rawMultipartStatus("/api/monster/rooms/ZZZZZZ/image-card", "", null, 4_900_000) + " " + (System.nanoTime() - t) / 1_000_000 + "ms");
        out("MONSTER-IMAGE", "10MB超 -> " + rawMultipartStatus("/api/monster/rooms/ZZZZZZ/image-card", "", null, 40 * 1024 * 1024));
        ex.shutdown();
    }

    // ---------- 6. レート制限（X-Forwarded-For） ----------
    @Test @Order(7)
    void rateLimitAndForwardedFor() throws Exception {
        // 同じ（偽装でない）XFFで6回 → 6回目が429か
        String fixed = "198.51.100." + (100 + N.incrementAndGet());
        List<Integer> same = new ArrayList<>();
        for (int i = 0; i < 7; i++) same.add(postJson("/api/monster/rooms", null, Map.of("X-Forwarded-For", fixed), "").status());
        out("RATELIMIT", "同一XFFで7回create=" + same);
        // XFFを毎回変える（プロキシが末尾を付け替えない場合の偽装を想定）
        List<Integer> spoof = new ArrayList<>();
        for (int i = 0; i < 12; i++) spoof.add(postJson("/api/monster/rooms", null, Map.of("X-Forwarded-For", "192.0.2." + (N.incrementAndGet() % 250)), "").status());
        out("RATELIMIT", "XFFを変えながら12回create=" + spoof);
        // 先頭だけ偽装し末尾は固定（Renderのようにプロキシが末尾へ追記する場合）
        String realTail = "198.51.100." + (100 + N.incrementAndGet());
        List<Integer> prefix = new ArrayList<>();
        for (int i = 0; i < 7; i++) prefix.add(postJson("/api/monster/rooms", null, Map.of("X-Forwarded-For", "6.6.6." + i + ", " + realTail), "").status());
        out("RATELIMIT", "先頭を偽装・末尾固定で7回create=" + prefix);
        // join の総当たり
        List<Integer> joins = new ArrayList<>();
        String jip = "198.51.100." + (100 + N.incrementAndGet());
        for (int i = 0; i < 12; i++) joins.add(postJson("/api/monster/rooms/AAAAA" + "ABCDEFGH".charAt(i % 8) + "/join", null, Map.of("X-Forwarded-For", jip), "").status());
        out("RATELIMIT", "joinを12回=" + joins);
        // XFFヘッダー無し・空・IPでない値
        out("RATELIMIT", "XFFなし=" + postJson("/api/monster/rooms", null, null, "").status() + " XFF='  '=" + postJson("/api/monster/rooms", null, Map.of("X-Forwarded-For", "  "), "").status());
    }

    @Test @Order(8)
    void redirectAndHsts() throws Exception {
        Res r = send("GET", "/admin/menu.html", null, Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "example.onrender.com"), null, null);
        out("REDIRECT", "status=" + r.status() + " Location=" + r.headers().firstValue("location").orElse("-")
                + " HSTS(X-Forwarded-Proto:https付き)=" + r.headers().firstValue("strict-transport-security").orElse("なし"));
        Res h = send("GET", "/api/health", null, Map.of("X-Forwarded-Proto", "https"), null, null);
        out("HSTS", "/api/health with X-Forwarded-Proto:https -> " + h.headers().firstValue("strict-transport-security").orElse("なし")
                + " / Referrer-Policy=" + h.headers().firstValue("referrer-policy").orElse("なし")
                + " / CSP=" + h.headers().firstValue("content-security-policy").orElse("なし")
                + " / Permissions-Policy=" + h.headers().firstValue("permissions-policy").orElse("なし"));
        Res cors = send("OPTIONS", "/api/monster/rooms", null, Map.of("Origin", "https://evil.example", "Access-Control-Request-Method", "POST"), null, null);
        out("CORS", "preflight from evil origin -> " + cors.status() + " ACAO=" + cors.headers().firstValue("access-control-allow-origin").orElse("なし"));
        Res g = send("GET", "/api/auth/csrf", null, Map.of("Origin", "https://evil.example"), null, null);
        out("CORS", "simple GET with Origin -> " + g.status() + " ACAO=" + g.headers().firstValue("access-control-allow-origin").orElse("なし"));
    }

    // ---------- テスト用Bean ----------
    @TestConfiguration
    public static class Cfg {
        @Bean @Primary
        UserDetailsService pocUsers() {
            BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
            return new InMemoryUserDetailsManager(
                    User.withUsername("poc-admin").password(enc.encode("password")).roles("ADMIN").build(),
                    User.withUsername("poc-admin2").password(enc.encode("password")).roles("ADMIN").build());
        }
        @Bean @Primary
        CountingAnalyzer countingAnalyzer() { return new CountingAnalyzer(); }
        @Bean @Primary
        MonsterAi pocMonsterAi() {
            return new MonsterAi() {
                public ReceiptText readReceipt(byte[] b, String m, String k) { return new ReceiptText(List.of("x"), null, null); }
                public Optional<MonsterParameters> generateParameters(ReceiptStructuredData d, int c, String k) { return Optional.empty(); }
                public Optional<String> illustrate(MonsterCard c, String k) { return Optional.empty(); }
                public Optional<CpuChoice> chooseCard(List<MonsterCard> h, String k) { return Optional.empty(); }
            };
        }
        @Bean @Primary
        com.example.receipt.config.GeminiApiKeys pocKeys() {
            return new com.example.receipt.config.GeminiApiKeys("default-key", "p1", "p2", "m", "a");
        }
    }

    public static class CountingAnalyzer implements ReceiptAnalyzer {
        final AtomicInteger calls = new AtomicInteger();
        public ReceiptText analyze(byte[] imageBytes, String mimeType) {
            calls.incrementAndGet();
            return new ReceiptText(List.of("stub line"), null, null);
        }
    }
}
