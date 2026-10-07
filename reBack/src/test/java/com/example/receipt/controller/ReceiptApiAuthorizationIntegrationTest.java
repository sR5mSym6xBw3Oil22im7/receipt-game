package com.example.receipt.controller;

import com.example.receipt.dto.ReceiptText;
import com.example.receipt.repository.ReceiptTableName;
import com.example.receipt.service.ReceiptAnalyzer;
import com.google.gson.JsonParser;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * レシートの解析・保存・一覧閲覧・詳細閲覧・削除の各APIが
 * 「ログイン済みの管理者だけが実行できる」ことをAPIごとに検証する。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:receipt-authz;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceiptApiAuthorizationIntegrationTest {
    private static final String MISSING_TABLE = "receipt_" + "0".repeat(32);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CountingReceiptAnalyzer analyzer;

    @BeforeEach
    void reset() {
        analyzer.calls = 0;
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS receipt_image_hash_registry (" +
                "image_sha256 VARCHAR(64) PRIMARY KEY, table_name VARCHAR(64) UNIQUE, " +
                "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }

    // ---- 1. 解析 POST /api/receipts/analyze ----

    @Test
    void analyzeRejectsAnonymousWithoutCallingGemini() throws Exception {
        mockMvc.perform(analyzeRequest()).andExpect(status().isUnauthorized());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void analyzeRejectsNonAdminUser() throws Exception {
        mockMvc.perform(analyzeRequest().with(user("reader").roles("USER")).with(csrfToken()))
                .andExpect(status().isForbidden());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void analyzeRejectsAdminWithoutCsrfToken() throws Exception {
        mockMvc.perform(analyzeRequest().session(loginAsAdmin())).andExpect(status().isForbidden());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void analyzeSucceedsForLoggedInAdmin() throws Exception {
        mockMvc.perform(analyzeRequest().session(loginAsAdmin()).with(csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0]").value("AUTHZ STORE"));
        assertThat(analyzer.calls).isEqualTo(1);
    }

    // ---- 2. 保存 POST /api/receipts/save ----

    @Test
    void saveRejectsAnonymousWithoutCreatingTable() throws Exception {
        String hash = reserveHash();
        mockMvc.perform(saveRequest(hash)).andExpect(status().isUnauthorized());
        assertHashNotSaved(hash);
    }

    @Test
    void saveRejectsNonAdminUser() throws Exception {
        String hash = reserveHash();
        mockMvc.perform(saveRequest(hash).with(user("reader").roles("USER")).with(csrfToken()))
                .andExpect(status().isForbidden());
        assertHashNotSaved(hash);
    }

    @Test
    void saveRejectsAdminWithoutCsrfToken() throws Exception {
        String hash = reserveHash();
        mockMvc.perform(saveRequest(hash).session(loginAsAdmin())).andExpect(status().isForbidden());
        assertHashNotSaved(hash);
    }

    @Test
    void saveSucceedsForLoggedInAdmin() throws Exception {
        String hash = reserveHash();
        mockMvc.perform(saveRequest(hash).session(loginAsAdmin()).with(csrfToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").exists());
        assertThat(savedTableName(hash)).isNotNull();
    }

    // ---- 3. 一覧閲覧 GET /api/receipts ----

    @Test
    void listRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/receipts")).andExpect(status().isUnauthorized());
    }

    @Test
    void listRejectsNonAdminUser() throws Exception {
        mockMvc.perform(get("/api/receipts").with(user("reader").roles("USER"))).andExpect(status().isForbidden());
    }

    @Test
    void listSucceedsForLoggedInAdmin() throws Exception {
        mockMvc.perform(get("/api/receipts").session(loginAsAdmin())).andExpect(status().isOk());
    }

    // ---- 4. 詳細閲覧 GET /api/receipts/{tableName} ----

    @Test
    void detailRejectsAnonymous() throws Exception {
        String tableName = createSavedReceipt();
        mockMvc.perform(get("/api/receipts/" + tableName)).andExpect(status().isUnauthorized());
    }

    @Test
    void detailRejectsNonAdminUser() throws Exception {
        String tableName = createSavedReceipt();
        mockMvc.perform(get("/api/receipts/" + tableName).with(user("reader").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void detailSucceedsForLoggedInAdmin() throws Exception {
        String tableName = createSavedReceipt();
        mockMvc.perform(get("/api/receipts/" + tableName).session(loginAsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").value(tableName));
    }

    // ---- 5. 削除 DELETE /api/receipts/{tableName} ----

    @Test
    void deleteRejectsAnonymousAndKeepsData() throws Exception {
        String tableName = createSavedReceipt();
        mockMvc.perform(delete("/api/receipts/" + tableName)).andExpect(status().isUnauthorized());
        assertThat(tableExists(tableName)).isTrue();
    }

    @Test
    void deleteRejectsNonAdminUserAndKeepsData() throws Exception {
        String tableName = createSavedReceipt();
        mockMvc.perform(delete("/api/receipts/" + tableName).with(user("reader").roles("USER")).with(csrfToken()))
                .andExpect(status().isForbidden());
        assertThat(tableExists(tableName)).isTrue();
    }

    @Test
    void deleteRejectsAdminWithoutCsrfTokenAndKeepsData() throws Exception {
        String tableName = createSavedReceipt();
        mockMvc.perform(delete("/api/receipts/" + tableName).session(loginAsAdmin()))
                .andExpect(status().isForbidden());
        assertThat(tableExists(tableName)).isTrue();
    }

    @Test
    void deleteSucceedsForLoggedInAdmin() throws Exception {
        String tableName = createSavedReceipt();
        mockMvc.perform(delete("/api/receipts/" + tableName).session(loginAsAdmin()).with(csrfToken()))
                .andExpect(status().isNoContent());
        assertThat(tableExists(tableName)).isFalse();
    }

    // ---- 横断確認 ----

    @Test
    void loggedOutSessionLosesAccessToEveryReceiptApi() throws Exception {
        MockHttpSession session = loginAsAdmin();
        mockMvc.perform(post("/api/auth/logout").session(session).with(csrfToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(analyzeRequest().session(session).with(csrfToken())).andExpect(status().isUnauthorized());
        mockMvc.perform(saveRequest(reserveHash()).session(session).with(csrfToken())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/receipts").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/receipts/" + MISSING_TABLE).session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/receipts/" + MISSING_TABLE).session(session).with(csrfToken()))
                .andExpect(status().isUnauthorized());
        assertThat(analyzer.calls).isZero();
    }

    @Test
    void loggedInNonAdminUserIsRejectedByEveryReceiptApi() throws Exception {
        MockHttpSession session = login("test-user");
        mockMvc.perform(get("/api/auth/session").session(session))
                .andExpect(jsonPath("$.authenticated").value(false));

        String hash = reserveHash();
        mockMvc.perform(analyzeRequest().session(session).with(csrfToken())).andExpect(status().isForbidden());
        mockMvc.perform(saveRequest(hash).session(session).with(csrfToken())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/receipts").session(session)).andExpect(status().isForbidden());
        String tableName = createSavedReceipt();
        mockMvc.perform(get("/api/receipts/" + tableName).session(session)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/receipts/" + tableName).session(session).with(csrfToken()))
                .andExpect(status().isForbidden());

        assertThat(analyzer.calls).isZero();
        assertHashNotSaved(hash);
        assertThat(tableExists(tableName)).isTrue();
    }

    @Test
    void loginDiscardsCsrfTokenIssuedBeforeLogin() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrfToken())
                        .contentType("application/json")
                        .content("{\"username\":\"test-admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cleared = login.getResponse().getCookie("XSRF-TOKEN");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    @Test
    void undeclaredApiPathIsDeniedEvenForAdmin() throws Exception {
        mockMvc.perform(get("/api/undeclared")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/undeclared").session(loginAsAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void uploadPageAndScriptRequireAdminLogin() throws Exception {
        mockMvc.perform(get("/admin/upload.html")).andExpect(redirectedUrl("/admin/login.html"));
        mockMvc.perform(get("/admin/app.js")).andExpect(redirectedUrl("/admin/login.html"));
        mockMvc.perform(get("/admin/select.html")).andExpect(redirectedUrl("/admin/login.html"));
        mockMvc.perform(get("/admin/upload.html").with(user("reader").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/upload.html").session(loginAsAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/admin/login.html")).andExpect(status().isOk());
    }

    private MockMultipartHttpServletRequestBuilder analyzeRequest() {
        return multipart("/api/receipts/analyze")
                .file(new MockMultipartFile("file", "sample.jpg", "image/jpeg", new byte[]{1, 2, 3, 4}))
                .param("geminiApiKey", "web-key");
    }

    private MockHttpServletRequestBuilder saveRequest(String hash) {
        return post("/api/receipts/save")
                .contentType("application/json")
                .content("{\"lines\":[\"AUTHZ TEST\"],\"sha256\":\"" + hash + "\"}");
    }

    private String createSavedReceipt() throws Exception {
        String hash = reserveHash();
        mockMvc.perform(saveRequest(hash).session(loginAsAdmin()).with(csrfToken()))
                .andExpect(status().isOk());
        return savedTableName(hash);
    }

    private String reserveHash() {
        String hash = UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
        jdbcTemplate.update("INSERT INTO receipt_image_hash_registry (image_sha256, table_name) VALUES (?, NULL)", hash);
        return hash;
    }

    private String savedTableName(String hash) {
        return jdbcTemplate.queryForObject(
                "SELECT table_name FROM receipt_image_hash_registry WHERE image_sha256 = ?", String.class, hash);
    }

    private void assertHashNotSaved(String hash) {
        assertThat(savedTableName(hash)).isNull();
    }

    private boolean tableExists(String tableName) {
        assertThat(ReceiptTableName.isSafe(tableName)).isTrue();
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, tableName) > 0;
    }

    private MockHttpSession loginAsAdmin() throws Exception {
        return login("test-admin");
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login").with(csrfToken())
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    private RequestPostProcessor csrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        String token = JsonParser.parseString(result.getResponse().getContentAsString())
                .getAsJsonObject().get("token").getAsString();
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    @TestConfiguration
    static class AuthorizationTestConfiguration {
        @Bean
        @Primary
        UserDetailsService testUserDetailsService() {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            return new InMemoryUserDetailsManager(
                    User.withUsername("test-admin").password(encoder.encode("password")).roles("ADMIN").build(),
                    User.withUsername("test-user").password(encoder.encode("password")).roles("USER").build());
        }

        @Bean
        @Primary
        CountingReceiptAnalyzer countingReceiptAnalyzer() {
            return new CountingReceiptAnalyzer();
        }
    }

    static class CountingReceiptAnalyzer implements ReceiptAnalyzer {
        volatile int calls;

        @Override
        public ReceiptText analyze(byte[] bytes, String mimeType, String geminiApiKey) {
            calls++;
            return new ReceiptText(List.of("AUTHZ STORE", "TOTAL 100"));
        }
    }
}
