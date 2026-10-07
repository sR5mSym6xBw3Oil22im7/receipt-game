package com.example.receipt.controller;

import com.example.receipt.dto.ReceiptText;
import com.example.receipt.service.ReceiptAnalyzer;
import com.example.receipt.repository.ReceiptTableName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "test-admin", roles = "ADMIN")
class ReceiptControllerIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CapturingReceiptAnalyzer analyzer;

    @BeforeEach
    void resetAnalyzer() {
        analyzer.lastGeminiApiKey = null;
        jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' AND table_name LIKE 'receipt_%'",
                String.class
        ).forEach(tableName -> jdbcTemplate.execute("DROP TABLE " + tableName));
    }

    @Test
    void legacyRootUploadEndpointCannotCreateReceiptTable() throws Exception {
        int before = receiptTableCount();

        mockMvc.perform(multipart("/api/receipts")
                        .with(user("test-admin").roles("ADMIN"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .file(sampleFile())
                        .param("geminiApiKey", "web-key"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(receiptTableCount()).isEqualTo(before);
        assertThat(analyzer.lastGeminiApiKey).isNull();
    }

    @Test
    void analyzeDoesNotCreateReceiptTable() throws Exception {
        int before = receiptTableCount();

        mockMvc.perform(multipart("/api/receipts/analyze")
                        .with(user("test-admin").roles("ADMIN"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .file(sampleFile())
                        .param("geminiApiKey", "web-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0]").value("SAMPLE STORE"));

        assertThat(receiptTableCount()).isEqualTo(before);
    }

    @Test
    void analyzePassesWebApiKeyToAnalyzer() throws Exception {
        MockMultipartFile file = sampleFile();

        mockMvc.perform(multipart("/api/receipts/analyze")
                        .with(user("test-admin").roles("ADMIN"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .file(file)
                        .param("geminiApiKey", "web-key-2"))
                .andExpect(status().isOk());

        assertThat(analyzer.lastGeminiApiKey).isEqualTo("web-key-2");
    }

    @Test
    void analyzeReturnsAndStoresImageSha256() throws Exception {
        mockMvc.perform(multipart("/api/receipts/analyze")
                        .with(user("test-admin").roles("ADMIN"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .file(sampleFile())
                        .param("geminiApiKey", "web-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sha256").value("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"));

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT image_sha256 FROM receipt_image_hash_registry",
                String.class
        );
        assertThat(storedHash).isEqualTo("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a");
    }

    @Test
    void analyzeAllowsAnImageHashThatWasAnalyzedButNotSaved() throws Exception {
        mockMvc.perform(multipart("/api/receipts/analyze")
                        .with(user("test-admin").roles("ADMIN"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .file(sampleFile())
                        .param("geminiApiKey", "web-key"))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/receipts/analyze")
                        .with(user("test-admin").roles("ADMIN"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .file(sampleFile())
                        .param("geminiApiKey", "web-key"))
                .andExpect(status().isOk());
    }

    @Test
    void analyzeRequiresWebApiKey() throws Exception {
        MockMultipartFile file = sampleFile();

        mockMvc.perform(multipart("/api/receipts/analyze")
                        .with(user("test-admin").roles("ADMIN"))
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .file(file))
                .andExpect(status().isBadRequest());
    }

    private MockMultipartFile sampleFile() {
        return new MockMultipartFile(
                "file",
                "sample.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3, 4}
        );
    }

    private int receiptTableCount() {
        return (int) jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) LIKE 'receipt_%'",
                String.class
        ).stream().filter(ReceiptTableName::isSafe).count();
    }

    @TestConfiguration
    static class StubAnalyzerConfiguration {
        @Bean
        @Primary
        CapturingReceiptAnalyzer stubReceiptAnalyzer() {
            return new CapturingReceiptAnalyzer();
        }
    }

    static class CapturingReceiptAnalyzer implements ReceiptAnalyzer {
        volatile String lastGeminiApiKey;

        @Override
        public ReceiptText analyze(byte[] bytes, String mimeType, String geminiApiKey) {
            lastGeminiApiKey = geminiApiKey;
            return new ReceiptText(List.of(
                    "SAMPLE STORE",
                    "ITEM 100",
                    "TOTAL 100"
            ));
        }
    }
}
