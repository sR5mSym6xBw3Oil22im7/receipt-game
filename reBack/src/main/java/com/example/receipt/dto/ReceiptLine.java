package com.example.receipt.dto;

public record ReceiptLine(
        int lineNo,
        String text
) {
}
