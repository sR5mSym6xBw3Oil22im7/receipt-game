package com.example.receipt.service;

import com.example.receipt.exception.ReceiptException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiReceiptAnalyzerTest {
    @Test
    void failsCleanlyWhenConfiguredApiKeyIsMissing() {
        GeminiReceiptAnalyzer analyzer = new GeminiReceiptAnalyzer("gemini-3.5-flash-lite", "");

        assertThatThrownBy(() -> analyzer.analyze(new byte[]{1, 2, 3}, "image/jpeg"))
                .isInstanceOfSatisfying(ReceiptException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("GEMINI_API_KEY_MISSING");
                });
    }
}
