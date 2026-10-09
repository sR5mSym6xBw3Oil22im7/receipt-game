package com.example.receipt.monster;

import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.monster.MonsterCardService.GeneratedCard;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.PersonalInfoSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DBに保存済みのレシート（テキスト）から、モンスターカードを1件ずつ作り直してDBへ保存する。
 * パラメータとイラストは保存時と同じく GEMINI_API_MONSTER → GEMINI_API_DEFAULT → 計算式の値・代替イラストの順に作る。
 * app.monster-batch.enabled=true のときだけ動き、終わったらアプリを終了する。
 * 結果は1件ごとに app.monster-batch.report（TSV）へ追記し、再実行では SAVED のレシートを飛ばして続きから処理する。
 */
@Component
@ConditionalOnProperty(name = "app.monster-batch.enabled", havingValue = "true")
public class MonsterCardRegenerator implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MonsterCardRegenerator.class);
    /** パラメータ・イラストとも生成AIで作れた。 */
    static final String SAVED = "SAVED";
    /** 一部または全部が計算式の値・代替イラスト。カードは保存し、再実行で作り直す。 */
    static final String PARTIAL = "PARTIAL";
    static final String FAILED = "FAILED";
    /** 生成AIがまったく使えないレシートがこの件数続いたら、利用上限とみなして止める。 */
    private static final int STOP_AFTER_NO_AI_IN_ROW = 3;

    private final MonsterCardService cardService;
    private final MonsterCardRepository cards;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext context;
    private final Path report;
    private final int maxAttempts;
    private final long intervalMillis;
    private final long retryWaitMillis;

    public MonsterCardRegenerator(
            MonsterCardService cardService,
            MonsterCardRepository cards,
            TransactionTemplate tx,
            ConfigurableApplicationContext context,
            @Value("${app.monster-batch.report:log/monster-batch.tsv}") String report,
            @Value("${app.monster-batch.max-attempts:3}") int maxAttempts,
            @Value("${app.monster-batch.interval-seconds:5}") long intervalSeconds,
            @Value("${app.monster-batch.retry-wait-seconds:60}") long retryWaitSeconds) {
        this.cardService = cardService;
        this.cards = cards;
        this.tx = tx;
        this.context = context;
        this.report = Path.of(report);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.intervalMillis = Math.max(0, intervalSeconds) * 1000;
        this.retryWaitMillis = Math.max(0, retryWaitSeconds) * 1000;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = runBatch();
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    /** 終了コード：0 すべて SAVED、1 PARTIAL・FAILED あり、2 利用上限とみなして途中停止。 */
    int runBatch() {
        Set<String> done = savedTablesInReport();
        List<String> targets = cards.savedReceiptTables().stream().filter(table -> !done.contains(table)).toList();
        LOGGER.info("Monster card regeneration started: receipts={}, alreadySaved={}", targets.size(), done.size());

        int saved = 0, partial = 0, failed = 0, noAiInRow = 0;
        boolean stopped = false;
        for (int i = 0; i < targets.size(); i++) {
            String table = targets.get(i);
            Result result = regenerate(table);
            switch (result.status()) {
                case SAVED -> saved++;
                case PARTIAL -> partial++;
                default -> failed++;
            }
            LOGGER.info("Monster card regeneration [{}/{}] {} {} {}", i + 1, targets.size(), table, result.status(), result.detail());
            appendReport(table, result);
            noAiInRow = result.noAi() ? noAiInRow + 1 : 0;
            if (noAiInRow >= STOP_AFTER_NO_AI_IN_ROW) {
                // 1日の利用上限など、待っても回復しない状態。回復後に再実行すれば SAVED 以外を続きから処理する
                LOGGER.warn("Monster card regeneration stopped: Gemini could not be used for {} receipts in a row. "
                        + "Re-run after the quota resets.", noAiInRow);
                stopped = true;
                break;
            }
            if (i + 1 < targets.size()) sleep(intervalMillis);
        }
        LOGGER.info("Monster card regeneration finished: saved={}, partial={}, failed={}, stopped={}",
                saved, partial, failed, stopped);
        return stopped ? 2 : partial + failed > 0 ? 1 : 0;
    }

    private Result regenerate(String table) {
        try {
            String sha = cards.imageShaOfReceipt(table).orElseThrow();
            ReceiptStructuredData data = cards.loadReceipt(table).orElse(null);
            List<String> lines = cards.tableExists(table) ? cards.receiptLines(table) : List.of();
            // 保存時に取り除いているが、念のため保存時と同じ検出・除去を通してから生成AIに渡す
            PersonalInfoSanitizer.Result clean = cardService.sanitizeForSave(data, lines);

            GeneratedCard best = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                GeneratedCard generated = cardService.generateForSave(sha, clean.data(), clean.lines());
                if (best == null || score(generated) > score(best)) best = generated;
                if (score(best) == 2) break;
                if (attempt < maxAttempts) {
                    LOGGER.warn("Monster card regeneration retry: table={}, attempt={}, aiParameters={}, aiIllustration={}",
                            table, attempt, generated.aiParameters(), generated.aiIllustration());
                    // 分単位の利用上限は1分ほどで回復する
                    sleep(retryWaitMillis * attempt);
                }
            }
            GeneratedCard chosen = best;
            tx.executeWithoutResult(status -> cardService.activateForSavedReceipt(table, chosen.card()));
            MonsterCard card = cards.findBySha(sha).orElseThrow().card();
            String detail = "id=" + card.id() + " name=" + card.name() + " element=" + card.element() + " rarity=" + card.rarity()
                    + " power=" + card.power() + " aiParameters=" + chosen.aiParameters() + " aiIllustration=" + chosen.aiIllustration();
            return new Result(score(chosen) == 2 ? SAVED : PARTIAL, detail, score(chosen) == 0);
        } catch (RuntimeException e) {
            LOGGER.warn("Monster card regeneration error: table={}, exception={}", table, e.toString());
            return new Result(FAILED, e.getClass().getSimpleName(), false);
        }
    }

    private static int score(GeneratedCard generated) {
        return (generated.aiParameters() ? 1 : 0) + (generated.aiIllustration() ? 1 : 0);
    }

    private Set<String> savedTablesInReport() {
        Set<String> tables = new HashSet<>();
        if (!Files.exists(report)) return tables;
        try {
            for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
                String[] columns = line.split("\t");
                if (columns.length >= 3 && SAVED.equals(columns[2])) tables.add(columns[1]);
            }
        } catch (IOException e) {
            LOGGER.warn("Monster batch report read failed: {}", e.toString());
        }
        return tables;
    }

    private void appendReport(String table, Result result) {
        String line = String.join("\t", OffsetDateTime.now().toString(), table, result.status(), result.detail()) + "\n";
        try {
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Monster batch report write failed: {}", e.toString());
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Monster card regeneration interrupted", e);
        }
    }

    private record Result(String status, String detail, boolean noAi) { }
}
