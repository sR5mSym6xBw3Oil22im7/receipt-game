package com.example.receipt.dto;

import java.util.List;

public record ReceiptUploadResponse(
        String tableName,
        int lineCount,
        List<String> lines,
        String sha256
) {
    public ReceiptUploadResponse(String tableName, int lineCount, List<String> lines) {
        this(tableName, lineCount, lines, null);
    }
}
