package com.example.receipt.service;

import com.example.receipt.dto.ReceiptText;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.dto.ReceiptUploadResponse;
import com.example.receipt.exception.ReceiptException;
import com.example.receipt.monster.MonsterCardService;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.PersonalInfoSanitizer;
import com.example.receipt.repository.ReceiptTableRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate tx;

    public ReceiptService(
            ReceiptAnalyzer receiptAnalyzer,
            ReceiptUploadValidator validator,
            ReceiptTableRepository repository,
            MonsterCardService monsterCardService,
            TransactionTemplate tx) {
        this.receiptAnalyzer = receiptAnalyzer;
        this.validator = validator;
        this.repository = repository;
        this.monsterCardService = monsterCardService;
        this.tx = tx;
    }

    public ReceiptText analyze(MultipartFile file) throws IOException {
        validator.validate(file);
        byte[] imageBytes = file.getBytes();
        String sha256 = sha256(imageBytes);
        // 利用者が申告したContent-Typeではなく、先頭バイトで判定した種別をGeminiへ渡す
        ReceiptText analyzed = receiptAnalyzer.analyze(
                imageBytes,
                ImageInspector.detectType(imageBytes)
        );
        repository.reserveImageHash(sha256);
        // モンスターカードの下書きは応答を返した後に別スレッドで作る（応答は変えない）
        monsterCardService.prepareDraftAsync(sha256, analyzed.lines(), analyzed.structuredData());
        return new ReceiptText(analyzed.lines(), sha256, analyzed.structuredData());
    }

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
        // モンスターカードはDBへの登録と同時に作る。Geminiの呼び出しは時間がかかるのでトランザクションの外で行う
        MonsterCard card = monsterCardService.prepareCardForSave(normalizedSha256, clean.data(), savedLines);

        // PostgreSQLとH2で同じ手順にする。H2はテーブル定義の変更でそれまでの処理を自動で確定させるため、
        // テーブル定義の変更はトランザクションの外で先に済ませ、トランザクションの中ではデータの登録・更新だけを行う。
        repository.ensureSaveSchema();
        // 保存済み（409 DUPLICATE_RECEIPT_IMAGE）、前回の保存が途中で失敗（409 INCOMPLETE_RECEIPT_SAVE）、
        // 解析時の確保が消えた（409 ANALYSIS_EXPIRED）ならテーブルを作る前に断る
        repository.requireImageNotSaved(normalizedSha256);
        String tableName = repository.createEmptyReceiptTable();
        try {
            return tx.execute(status -> {
                repository.insertReceiptLines(tableName, savedLines);
                repository.insertStructuredData(tableName, normalizedSha256, clean.data());
                monsterCardService.activateForSavedReceipt(tableName, card);
                // ハッシュの登録は最後に行う。ここまでのどこで失敗しても、ハッシュは「未保存」のまま残る
                repository.registerImageHash(normalizedSha256, tableName);
                return new ReceiptUploadResponse(tableName, savedLines.size(), savedLines, normalizedSha256);
            });
        } catch (RuntimeException e) {
            // トランザクションは取り消されたので、トランザクションの外で作ったテーブルも削除する
            repository.dropReceiptTableQuietly(tableName);
            throw e;
        }
    }

    static String sha256(byte[] bytes) {
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
