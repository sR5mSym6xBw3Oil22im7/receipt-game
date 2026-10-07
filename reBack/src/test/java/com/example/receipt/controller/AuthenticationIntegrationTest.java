package com.example.receipt.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import jakarta.servlet.http.Cookie;
import org.springframework.jdbc.core.JdbcTemplate;
import com.google.gson.JsonParser;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:receipt-auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void allReceiptApisRejectUnauthenticatedRequestsAndHealthIsPublic() throws Exception {
        mockMvc.perform(get("/api/receipts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/receipts/receipt_deadbeef")).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/receipts/analyze").file("file", new byte[]{1}).param("geminiApiKey", "not-an-auth-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/receipts").file("file", new byte[]{1}))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/receipts/save").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/receipts/receipt_deadbeef"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void authenticatedAdminCanAccessReceiptApiButMustPresentCsrfForMutations() throws Exception {
        mockMvc.perform(get("/api/receipts").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/receipts").with(user("reader").roles("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/receipts/receipt_missing").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());

        String hash = newHash();
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS receipt_image_hash_registry (" +
                "image_sha256 VARCHAR(64) PRIMARY KEY, table_name VARCHAR(64) UNIQUE, " +
                "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO receipt_image_hash_registry (image_sha256, table_name) VALUES (?, NULL)", hash);
        mockMvc.perform(post("/api/receipts/save").with(user("admin").roles("ADMIN"))
                        .contentType("application/json")
                        .content("{\"lines\":[\"NO CSRF\"],\"sha256\":\"" + hash + "\"}"))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipt_image_hash_registry WHERE image_sha256 = ? AND table_name IS NULL",
                Integer.class, hash)).isEqualTo(1);
    }

    @Test
    void loginSessionAndLogoutProtectReceiptApi() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        String csrfToken = JsonParser.parseString(csrfResult.getResponse().getContentAsString())
                .getAsJsonObject().get("token").getAsString();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        MvcResult login = mockMvc.perform(post("/api/auth/login").cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfToken)
                        .contentType("application/json")
                        .content("{\"username\":\"test-admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/receipts").session(session)).andExpect(status().isOk());
        String authorizedDeleteTable = saveReceipt(session, newHash());
        String deniedDeleteTable = saveReceipt(session, newHash());
        mockMvc.perform(get("/api/receipts/" + deniedDeleteTable).session(session))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/receipts/" + authorizedDeleteTable).session(session).with(csrfToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").session(session).cookie(csrfCookie).header("X-XSRF-TOKEN", csrfToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/receipts").session(session)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/receipts/" + deniedDeleteTable).session(session))
                .andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, authorizedDeleteTable)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, deniedDeleteTable)).isEqualTo(1);
    }

    @Test
    void invalidCredentialsDoNotCreateAuthenticatedSession() throws Exception {
        mockMvc.perform(post("/api/auth/login").with(csrfToken())
                        .contentType("application/json")
                        .content("{\"username\":\"test-admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedLoginFailuresLockTheAccountEvenForCorrectPassword() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login").with(csrfToken())
                            .contentType("application/json")
                            .content("{\"username\":\"lock-test-admin\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login").with(csrfToken())
                        .contentType("application/json")
                        .content("{\"username\":\"lock-test-admin\",\"password\":\"password\"}"))
                .andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/api/auth/login").with(csrfToken())
                        .contentType("application/json")
                        .content("{\"username\":\"test-admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void sessionStatusIsPublicAndAnonymousStateIsFalse() throws Exception {
        mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.authenticated").value(false));
    }

    private String saveReceipt(MockHttpSession session, String hash) throws Exception {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS receipt_image_hash_registry (" +
                "image_sha256 VARCHAR(64) PRIMARY KEY, table_name VARCHAR(64) UNIQUE, " +
                "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.update("INSERT INTO receipt_image_hash_registry (image_sha256, table_name) VALUES (?, NULL)", hash);
        MvcResult result = mockMvc.perform(post("/api/receipts/save").session(session).with(csrfToken())
                        .contentType("application/json")
                        .content("{\"lines\":[\"AUTH TEST\"],\"sha256\":\"" + hash + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonParser.parseString(result.getResponse().getContentAsString())
                .getAsJsonObject().get("tableName").getAsString();
    }

    private String newHash() {
        return java.util.UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000";
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
    static class AuthenticationTestConfiguration {
        @Bean
        @Primary
        UserDetailsService testUserDetailsService() {
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            return new InMemoryUserDetailsManager(
                    User.withUsername("test-admin").password(encoder.encode("password")).roles("ADMIN").build(),
                    User.withUsername("lock-test-admin").password(encoder.encode("password")).roles("ADMIN").build());
        }
    }
}
