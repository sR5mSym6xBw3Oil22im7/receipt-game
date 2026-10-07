package com.example.receipt.dto;

import java.math.BigDecimal;

public record ReceiptItemData(String name, String category, BigDecimal quantity, Long unitPrice, Long amount) {
}
