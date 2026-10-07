package com.example.receipt.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ReceiptDetail(
        String tableName,
        int lineCount,
        OffsetDateTime createdAt,
        List<ReceiptLine> lines
) {
}
