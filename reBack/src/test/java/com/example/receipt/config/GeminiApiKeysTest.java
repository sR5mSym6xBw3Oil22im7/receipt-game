package com.example.receipt.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiApiKeysTest {
    @Test
    void usesDefaultWhenSpecificKeysAreBlank() {
        GeminiApiKeys keys = new GeminiApiKeys("default", "", " ", null, "");
        keys.validate();

        assertThat(keys.player1()).isEqualTo("default");
        assertThat(keys.player2()).isEqualTo("default");
        assertThat(keys.monster()).isEqualTo("default");
        assertThat(keys.analyze()).isEqualTo("default");
    }

    @Test
    void specificKeyTakesPrecedenceOverDefault() {
        GeminiApiKeys keys = new GeminiApiKeys("default", "p1", "", "", "an");
        keys.validate();

        assertThat(keys.player1()).isEqualTo("p1");
        assertThat(keys.player2()).isEqualTo("default");
        assertThat(keys.analyze()).isEqualTo("an");
    }

    @Test
    void refusesToStartWithoutDefault() {
        GeminiApiKeys keys = new GeminiApiKeys("", "p1", "p2", "m", "an");

        assertThatThrownBy(keys::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_DEFAULT");
    }
}
