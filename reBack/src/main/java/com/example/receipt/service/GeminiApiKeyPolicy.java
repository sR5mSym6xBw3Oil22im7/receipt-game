package com.example.receipt.service;

public final class GeminiApiKeyPolicy {
    static final int MAX_API_KEY_LENGTH = 512;

    private GeminiApiKeyPolicy() {
    }

    public static String requireValid(String apiKey) {
        String normalized = normalize(apiKey);

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Gemini API key is required.");
        }
        if (normalized.length() > MAX_API_KEY_LENGTH) {
            throw new IllegalArgumentException("Gemini API key is too long.");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
