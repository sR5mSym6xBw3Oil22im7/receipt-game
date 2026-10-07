package com.example.receipt.dto;

import java.util.List;

public record ReceiptText(List<String> lines, String sha256, ReceiptStructuredData structuredData) {
    public ReceiptText(List<String> lines, String sha256) { this(lines, sha256, null); }
    public ReceiptText(List<String> lines) {
        this(lines, null, null);
    }
}
