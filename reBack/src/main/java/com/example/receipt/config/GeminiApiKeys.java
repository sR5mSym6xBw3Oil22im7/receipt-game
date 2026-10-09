package com.example.receipt.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用途別のGemini APIキー。用途別の値が未設定（空）の場合は GEMINI_API_DEFAULT を使う。
 * GEMINI_API_DEFAULT が未設定の場合は起動を中止する。
 */
@Component
public class GeminiApiKeys {
    private final String defaultKey;
    private final String player1;
    private final String player2;
    private final String monster;
    private final String analyze;

    public GeminiApiKeys(
            @Value("${gemini.default-api-key:}") String defaultKey,
            @Value("${gemini.player1-api-key:}") String player1,
            @Value("${gemini.player2-api-key:}") String player2,
            @Value("${gemini.monster-api-key:}") String monster,
            @Value("${gemini.analyze-api-key:}") String analyze) {
        this.defaultKey = normalize(defaultKey);
        this.player1 = normalize(player1);
        this.player2 = normalize(player2);
        this.monster = normalize(monster);
        this.analyze = normalize(analyze);
    }

    @PostConstruct
    void validate() {
        if (defaultKey.isEmpty()) {
            throw new IllegalStateException(
                    "GEMINI_API_DEFAULT is required. Set it in the environment or reBack/.env.");
        }
    }

    public String defaultKey() {
        return defaultKey;
    }

    public String player1() {
        return orDefault(player1);
    }

    public String player2() {
        return orDefault(player2);
    }

    public String monster() {
        return orDefault(monster);
    }

    public String analyze() {
        return orDefault(analyze);
    }

    private String orDefault(String specific) {
        return specific.isEmpty() ? defaultKey : specific;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
