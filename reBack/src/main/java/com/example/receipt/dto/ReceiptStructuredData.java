package com.example.receipt.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ReceiptStructuredData(
        String storeName, String branchName, String storeCategory, LocalDateTime purchasedAt,
        Long totalAmount, String paymentMethod, String receiptNumber, List<ReceiptItemData> items) {
    public List<ReceiptItemData> safeItems() { return items == null ? List.of() : items; }
}
