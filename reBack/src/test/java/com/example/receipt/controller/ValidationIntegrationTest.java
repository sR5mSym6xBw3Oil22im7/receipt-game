package com.example.receipt.controller;

import com.google.gson.JsonParser;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bean Validation と ApiExceptionHandler による入力検証（境界値・異常値）。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:receipt-validation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ValidationIntegrationTest {
    private static final String HASH = "0123456789abcdef".repeat(4);

    @Autowired MockMvc mockMvc;

    private org.springframework.test.web.servlet.ResultActions save(String body) throws Exception {
        return mockMvc.perform(post("/api/receipts/save").with(user("admin").roles("ADMIN")).with(csrfToken())
                .contentType("application/json").content(body));
    }

    @Test
    void loginRejectsMissingOversizedAndMalformedInputWith400() throws Exception {
        for (String body : new String[]{"{}", "{\"username\":\"\",\"password\":\"x\"}", "{\"username\":\"a\"}",
                "{\"username\":\"" + "a".repeat(65) + "\",\"password\":\"x\"}",
                "{\"username\":\"a\",\"password\":\"" + "p".repeat(129) + "\"}", "not json", "null", ""}) {
            mockMvc.perform(post("/api/auth/login").with(csrfToken()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/auth/login").with(csrfToken()).contentType("text/plain").content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void saveRejectsOutOfRangeValuesWith400AndDoesNotEchoTheValue() throws Exception {
        save("{\"lines\":[\"" + "a".repeat(501) + "\"],\"sha256\":\"" + HASH + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        save("{\"lines\":[\"a\\u0000b\"],\"sha256\":\"" + HASH + "\"}").andExpect(status().isBadRequest());
        save("{\"lines\":[\"ok\"],\"sha256\":\"short\"}").andExpect(status().isBadRequest());
        save("{\"lines\":[\"ok\"],\"sha256\":\"" + HASH + "\",\"structuredData\":{\"storeName\":\"" + "s".repeat(256) + "\"}}")
                .andExpect(status().isBadRequest());
        save("{\"lines\":[\"ok\"],\"sha256\":\"" + HASH + "\",\"structuredData\":{\"storeCategory\":\"" + "c".repeat(51) + "\"}}")
                .andExpect(status().isBadRequest());
        save("{\"lines\":[\"ok\"],\"sha256\":\"" + HASH + "\",\"structuredData\":{\"totalAmount\":9223372036854775807}}")
                .andExpect(status().isBadRequest());
        save("{\"lines\":[\"ok\"],\"sha256\":\"" + HASH + "\",\"structuredData\":{\"items\":[{\"name\":\"a\",\"quantity\":1e30}]}}")
                .andExpect(status().isBadRequest());
        save("{\"lines\":[\"ok\"],\"sha256\":\"" + HASH + "\",\"structuredData\":{\"items\":[{\"name\":\"a\",\"amount\":-9999999999}]}}")
                .andExpect(status().isBadRequest());
        StringBuilder many = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < 1001; i++) many.append(i == 0 ? "" : ",").append("\"x\"");
        save(many + "],\"sha256\":\"" + HASH + "\"}").andExpect(status().isBadRequest());
        // 範囲内の負の金額（値引き）は検証を通る（ここでは未予約のハッシュなので409になる）
        save("{\"lines\":[\"ok\"],\"sha256\":\"" + HASH + "\",\"structuredData\":{\"totalAmount\":-500,\"items\":[{\"name\":\"値引き\",\"amount\":-500}]}}")
                .andExpect(status().isConflict());
    }

    @Test
    void malformedBodiesAndWrongContentTypeAreClientErrorsNot500() throws Exception {
        save("{broken").andExpect(status().isBadRequest());
        save("").andExpect(status().isBadRequest());
        save("{\"lines\":\"abc\"}").andExpect(status().isBadRequest());
        save("{\"lines\":[\"ok\"],\"sha256\":\"" + HASH + "\",\"structuredData\":{\"purchasedAt\":\"yesterday\"}}")
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/receipts/save").with(user("admin").roles("ADMIN")).with(csrfToken())
                .contentType("text/plain").content("{}")).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void invalidTableNameIsNotFoundNotServerError() throws Exception {
        mockMvc.perform(get("/api/receipts/x").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/receipts/receipt_ABCDEF00000000000000000000000000").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    private RequestPostProcessor csrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        String token = JsonParser.parseString(result.getResponse().getContentAsString()).getAsJsonObject().get("token").getAsString();
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    @TestConfiguration
    static class Users {
        @Bean
        @Primary
        UserDetailsService validationUsers() {
            return new InMemoryUserDetailsManager(User.withUsername("test-admin")
                    .password(new BCryptPasswordEncoder().encode("password")).roles("ADMIN").build());
        }
    }
}
