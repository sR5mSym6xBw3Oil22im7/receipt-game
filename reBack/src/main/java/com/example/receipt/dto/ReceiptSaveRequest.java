package com.example.receipt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ReceiptSaveRequest(
        @Size(max = ValidationRules.MAX_LINES, message = "linesは" + ValidationRules.MAX_LINES + "行以内にしてください。")
        List<@Size(max = ValidationRules.MAX_LINE_LENGTH, message = "1行は" + ValidationRules.MAX_LINE_LENGTH + "文字以内にしてください。")
                @Pattern(regexp = ValidationRules.NO_NUL, message = "制御文字（NUL）は使えません。") String> lines,
        @Pattern(regexp = "[0-9a-fA-F]{64}", message = "画像ハッシュ値が不正です。")
        String sha256,
        @Valid ReceiptStructuredData structuredData) {
    public ReceiptSaveRequest(List<String> lines, String sha256) { this(lines, sha256, null); }
    public ReceiptSaveRequest(List<String> lines) {
        this(lines, null, null);
    }
}
