package com.example.receipt.service;

import com.example.receipt.exception.ReceiptException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiReceiptAnalyzerTest {
    @Test
    void failsCleanlyWhenWebApiKeyIsMissing() {
        GeminiReceiptAnalyzer analyzer = new GeminiReceiptAnalyzer("gemini-3.5-flash-lite");

        assertThatThrownBy(() -> analyzer.analyze(new byte[]{1, 2, 3}, "image/jpeg", ""))
                .isInstanceOfSatisfying(ReceiptException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("GEMINI_API_KEY_MISSING");
                });
    }
}
