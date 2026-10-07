package com.example.receipt.dto;

import java.time.OffsetDateTime;

public record ReceiptSummary(
        String tableName,
        int lineCount,
        OffsetDateTime createdAt
) {
}
