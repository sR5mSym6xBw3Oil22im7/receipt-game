package com.example.receipt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public record ReceiptStructuredData(
        @Size(max = ValidationRules.MAX_NAME) @Pattern(regexp = ValidationRules.NO_NUL) String storeName,
        @Size(max = ValidationRules.MAX_NAME) @Pattern(regexp = ValidationRules.NO_NUL) String branchName,
        @Size(max = ValidationRules.MAX_CATEGORY) @Pattern(regexp = ValidationRules.NO_NUL) String storeCategory,
        LocalDateTime purchasedAt,
        @Min(-ValidationRules.MAX_AMOUNT) @Max(ValidationRules.MAX_AMOUNT) Long totalAmount,
        @Size(max = ValidationRules.MAX_PAYMENT) @Pattern(regexp = ValidationRules.NO_NUL) String paymentMethod,
        @Size(max = ValidationRules.MAX_RECEIPT_NUMBER) @Pattern(regexp = ValidationRules.NO_NUL) String receiptNumber,
        @Size(max = ValidationRules.MAX_ITEMS) List<@Valid ReceiptItemData> items) {
    public List<ReceiptItemData> safeItems() { return items == null ? List.of() : items; }
}
