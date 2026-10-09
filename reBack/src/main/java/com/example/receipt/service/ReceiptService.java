package com.example.receipt.service;

import com.example.receipt.dto.ReceiptText;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.dto.ReceiptUploadResponse;
import com.example.receipt.exception.ReceiptException;
import com.example.receipt.monster.MonsterCardService;
import com.example.receipt.monster.engine.PersonalInfoSanitizer;
import com.example.receipt.repository.ReceiptTableRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class ReceiptService {
    private final ReceiptAnalyzer receiptAnalyzer;
    private final ReceiptUploadValidator validator;
    private final ReceiptTableRepository repository;
    private final MonsterCardService monsterCardService;

    public ReceiptService(
            ReceiptAnalyzer receiptAnalyzer,
            ReceiptUploadValidator validator,
            ReceiptTableRepository repository,
            MonsterCardService monsterCardService) {
        this.receiptAnalyzer = receiptAnalyzer;
        this.validator = validator;
        this.repository = repository;
        this.monsterCardService = monsterCardService;
    }

    public ReceiptText analyze(MultipartFile file) throws IOException {
        validator.validate(file);
        byte[] imageBytes = file.getBytes();
        String sha256 = sha256(imageBytes);
        ReceiptText analyzed = receiptAnalyzer.analyze(
                imageBytes,
                file.getContentType()
        );
        repository.reserveImageHash(sha256);
        // モンスターカードの下書きは応答を返した後に別スレッドで作る（応答は変えない）
        monsterCardService.prepareDraftAsync(sha256, analyzed.lines(), analyzed.structuredData());
        return new ReceiptText(analyzed.lines(), sha256, analyzed.structuredData());
    }

    @Transactional
    public ReceiptUploadResponse store(java.util.List<String> lines, String sha256, ReceiptStructuredData structuredData) {
        java.util.List<String> originalLines = lines == null
                ? java.util.List.of()
                : lines.stream().filter(java.util.Objects::nonNull).toList();
        if (originalLines.isEmpty()) {
            throw new ReceiptException(HttpStatus.BAD_REQUEST, "EMPTY_RECEIPT", "レシートデータが空です。");
        }
        String normalizedSha256 = normalizeSha256(sha256);
        // 保存前に個人情報を検出して取り除く。取り除けなければ保存しない（カードも作らない）。
        PersonalInfoSanitizer.Result clean = monsterCardService.sanitizeForSave(structuredData, originalLines);
        java.util.List<String> savedLines = clean.lines();
        String tableName = repository.createReceiptTableAndInsert(savedLines, normalizedSha256);
        repository.saveStructuredData(tableName, normalizedSha256, clean.data());
        monsterCardService.activateForSavedReceipt(tableName, normalizedSha256, clean.data(), savedLines);
        return new ReceiptUploadResponse(tableName, savedLines.size(), savedLines, normalizedSha256);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String normalizeSha256(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new ReceiptException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_HASH", "画像ハッシュ値が不正です。");
        }
        return sha256.toLowerCase(java.util.Locale.ROOT);
    }
}
