package com.example.receipt.dto;

import java.util.List;

public record ReceiptSaveRequest(List<String> lines, String sha256, ReceiptStructuredData structuredData) {
    public ReceiptSaveRequest(List<String> lines, String sha256) { this(lines, sha256, null); }
    public ReceiptSaveRequest(List<String> lines) {
        this(lines, null, null);
    }
}
