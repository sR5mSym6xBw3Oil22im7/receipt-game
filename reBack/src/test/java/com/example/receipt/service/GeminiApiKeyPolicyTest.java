package com.example.receipt.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiApiKeyPolicyTest {
    @Test
    void acceptsAndTrimsWebApiKey() {
        assertThat(GeminiApiKeyPolicy.requireValid("  web-key  "))
                .isEqualTo("web-key");
    }

    @Test
    void rejectsBlankWebApiKey() {
        assertThatThrownBy(() -> GeminiApiKeyPolicy.requireValid("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnreasonablyLongWebApiKey() {
        String tooLong = "x".repeat(GeminiApiKeyPolicy.MAX_API_KEY_LENGTH + 1);

        assertThatThrownBy(() -> GeminiApiKeyPolicy.requireValid(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
