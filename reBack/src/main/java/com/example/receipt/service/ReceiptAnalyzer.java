package com.example.receipt.service;

import com.example.receipt.dto.ReceiptText;

public interface ReceiptAnalyzer {
    ReceiptText analyze(byte[] imageBytes, String mimeType, String geminiApiKey);
}
