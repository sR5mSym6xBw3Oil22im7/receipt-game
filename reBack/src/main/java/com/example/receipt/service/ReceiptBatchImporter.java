package com.example.receipt.service;

import com.example.receipt.dto.ReceiptText;
import com.example.receipt.dto.ReceiptUploadResponse;
import com.example.receipt.exception.ReceiptException;
import com.example.receipt.repository.ReceiptTableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * フォルダ内のレシート画像を1件ずつ解析し、管理画面の「解析→保存」と同じ処理でDBへ保存する。
 * app.batch-import.dir を指定したときだけ動き、終わったらアプリを終了する。
 * 保存済みの画像は解析せずに飛ばすため、途中で止めても再実行すれば続きから処理する。
 * 結果は1件ごとに app.batch-import.report（TSV）へ追記する。
 */
@Component
@ConditionalOnProperty("app.batch-import.dir")
public class ReceiptBatchImporter implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReceiptBatchImporter.class);
    /** 一時的な失敗として待って再試行するエラー。NO_TEXT_FOUND も読み直しで取れることがあるため含める。 */
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "GEMINI_QUOTA_EXCEEDED", "GEMINI_API_ERROR", "EMPTY_ANALYSIS_RESULT",
            "INVALID_ANALYSIS_RESULT", "NO_TEXT_FOUND");
    /** 分単位の上限なら数分で回復する。この回数続けて上限なら1日の上限とみなして止める。 */
    private static final int QUOTA_ATTEMPTS = 3;
    private static final String QUOTA_STOPPED = "QUOTA_STOPPED";

    private final ReceiptAnalyzer analyzer;
    private final ReceiptService receiptService;
    private final ReceiptTableRepository repository;
    private final ConfigurableApplicationContext context;
    private final Path directory;
    private final Path report;
    private final int maxAttempts;
    private final long intervalMillis;

    public ReceiptBatchImporter(
            ReceiptAnalyzer analyzer,
            ReceiptService receiptService,
            ReceiptTableRepository repository,
            ConfigurableApplicationContext context,
            @Value("${app.batch-import.dir}") String directory,
            @Value("${app.batch-import.report:log/batch-import.tsv}") String report,
            @Value("${app.batch-import.max-attempts:5}") int maxAttempts,
            @Value("${app.batch-import.interval-seconds:5}") long intervalSeconds) {
        this.analyzer = analyzer;
        this.receiptService = receiptService;
        this.repository = repository;
        this.context = context;
        this.directory = Path.of(directory);
        this.report = Path.of(report);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.intervalMillis = Math.max(0, intervalSeconds) * 1000;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Path> images;
        try (Stream<Path> files = Files.list(directory)) {
            images = files.filter(Files::isRegularFile).filter(file -> mimeType(file) != null).sorted().toList();
        }
        Files.createDirectories(report.toAbsolutePath().getParent());
        LOGGER.info("Receipt batch import started: dir={}, images={}", directory, images.size());

        int saved = 0, skipped = 0, failed = 0;
        boolean quotaStopped = false;
        for (int i = 0; i < images.size(); i++) {
            Path image = images.get(i);
            Result result = importOne(image);
            if (QUOTA_STOPPED.equals(result.status())) {
                // 1日の利用上限など、待っても回復しない上限。残りは失敗にせず、回復後の再実行で続きから処理する
                LOGGER.warn("Receipt batch import stopped: Gemini quota exhausted at [{}/{}] {}. Re-run after the quota resets.",
                        i + 1, images.size(), image.getFileName());
                quotaStopped = true;
                break;
            }
            switch (result.status()) {
                case "SAVED" -> saved++;
                case "SKIPPED" -> skipped++;
                default -> failed++;
            }
            LOGGER.info("Receipt batch import [{}/{}] {} {} {}", i + 1, images.size(),
                    image.getFileName(), result.status(), result.detail());
            appendReport(image, result);
            if ("SAVED".equals(result.status()) && i + 1 < images.size()) Thread.sleep(intervalMillis);
        }
        LOGGER.info("Receipt batch import finished: saved={}, skipped={}, failed={}, quotaStopped={}",
                saved, skipped, failed, quotaStopped);
        int exitCode = quotaStopped ? 2 : failed > 0 ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private Result importOne(Path image) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(image);
        } catch (IOException e) {
            return new Result("FAILED", "READ_ERROR", null, 0);
        }
        String sha256 = ReceiptService.sha256(bytes);
        if (repository.isImageSaved(sha256)) return new Result("SKIPPED", "ALREADY_SAVED", null, 0);

        String lastCode = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ReceiptText analyzed = analyzer.analyze(bytes, mimeType(image));
                repository.reserveImageHash(sha256);
                ReceiptUploadResponse response = receiptService.store(analyzed.lines(), sha256, analyzed.structuredData());
                return new Result("SAVED", "attempt=" + attempt, response.tableName(), response.lineCount());
            } catch (ReceiptException e) {
                lastCode = e.code();
                if ("DUPLICATE_RECEIPT_IMAGE".equals(lastCode)) return new Result("SKIPPED", "ALREADY_SAVED", null, 0);
                if ("GEMINI_QUOTA_EXCEEDED".equals(lastCode) && attempt >= QUOTA_ATTEMPTS) {
                    return new Result(QUOTA_STOPPED, lastCode, null, 0);
                }
                if (!RETRYABLE_CODES.contains(lastCode) || attempt == maxAttempts) break;
                LOGGER.warn("Receipt batch import retry: file={}, attempt={}, code={}",
                        image.getFileName(), attempt, lastCode);
                // 利用上限は1分単位で回復するため長めに待つ
                sleep("GEMINI_QUOTA_EXCEEDED".equals(lastCode) ? 60_000L * attempt : 10_000L * attempt);
            } catch (RuntimeException e) {
                lastCode = e.getClass().getSimpleName();
                LOGGER.warn("Receipt batch import error: file={}, attempt={}, exception={}",
                        image.getFileName(), attempt, e.toString());
                if (attempt == maxAttempts) break;
                sleep(10_000L * attempt);
            }
        }
        return new Result("FAILED", lastCode, null, 0);
    }

    private void appendReport(Path image, Result result) {
        String line = String.join("\t", OffsetDateTime.now().toString(), image.getFileName().toString(),
                result.status(), String.valueOf(result.detail()),
                result.tableName() == null ? "" : result.tableName(), String.valueOf(result.lineCount())) + "\n";
        try {
            Files.writeString(report, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Receipt batch import report write failed: {}", e.toString());
        }
    }

    private static String mimeType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Receipt batch import interrupted", e);
        }
    }

    private record Result(String status, String detail, String tableName, int lineCount) { }
}
