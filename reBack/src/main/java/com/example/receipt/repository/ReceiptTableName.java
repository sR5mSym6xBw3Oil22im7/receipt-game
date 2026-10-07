package com.example.receipt.repository;

import java.util.UUID;
import java.util.regex.Pattern;

public final class ReceiptTableName {
    private static final Pattern SAFE = Pattern.compile("receipt_[0-9a-f]{32}");

    private ReceiptTableName() {
    }

    public static String generate() {
        return "receipt_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isSafe(String value) {
        return value != null && SAFE.matcher(value).matches();
    }
}
