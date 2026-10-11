package com.example.receipt.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ReceiptItemData(
        @Size(max = ValidationRules.MAX_NAME, message = "品目名は255文字以内にしてください。")
        @Pattern(regexp = ValidationRules.NO_NUL, message = "制御文字（NUL）は使えません。") String name,
        @Size(max = ValidationRules.MAX_CATEGORY, message = "商品カテゴリーは50文字以内にしてください。")
        @Pattern(regexp = ValidationRules.NO_NUL, message = "制御文字（NUL）は使えません。") String category,
        @Digits(integer = 9, fraction = 3, message = "数量が範囲外です。") BigDecimal quantity,
        @Min(-ValidationRules.MAX_AMOUNT) @Max(ValidationRules.MAX_AMOUNT) Long unitPrice,
        @Min(-ValidationRules.MAX_AMOUNT) @Max(ValidationRules.MAX_AMOUNT) Long amount) {
}
