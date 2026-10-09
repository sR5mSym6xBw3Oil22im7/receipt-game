package com.example.receipt.monster;

import com.example.receipt.config.GeminiApiKeys;
import com.example.receipt.dto.ReceiptStructuredData;
import com.example.receipt.exception.ReceiptException;
import com.example.receipt.monster.engine.MonsterCard;
import com.example.receipt.monster.engine.MonsterCardGenerator;
import com.example.receipt.monster.engine.PersonalInfoSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * レシート解析・保存・削除とカードの連動（要件定義書 9章・10章）。
 * 解析：別スレッドで下書きを作る。保存：個人情報を取り除いてから保存し、カードを有効にする。
 */
@Service
public class MonsterCardService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MonsterCardService.class);
    public static final String PII_FAILED_MESSAGE = "個人情報の確認ができなかったため保存できません。";

    private final MonsterCardRepository cards;
    private final MonsterAi ai;
    private final GeminiApiKeys keys;
    private final MonsterSettings settings;
    private final MonsterRateLimiter limiter;
    private final MonsterTaskRunner tasks;
    private final TransactionTemplate tx;
    // テストで「除去の処理が失敗した場合」を再現するために差し替えられるようにしている
    private BiFunction<ReceiptStructuredData, List<String>, PersonalInfoSanitizer.Result> sanitizer = PersonalInfoSanitizer::sanitize;

    public MonsterCardService(MonsterCardRepository cards, MonsterAi ai, GeminiApiKeys keys, MonsterSettings settings,
                              MonsterRateLimiter limiter, MonsterTaskRunner tasks, TransactionTemplate tx) {
        this.cards = cards;
        this.ai = ai;
        this.keys = keys;
        this.settings = settings;
        this.limiter = limiter;
        this.tasks = tasks;
        this.tx = tx;
        cards.ensureSchema();
    }

    /**
     * 解析の応答を返した後、別スレッドで下書きのカードを作る。同じ画像のカードがあれば作り直さない。
     * 保存時に GEMINI_API_MONSTER で作り直して上書きするので、ここでは生成AIを使わない（利用回数を二重に使わないため）。
     */
    public void prepareDraftAsync(String sha256, List<String> lines, ReceiptStructuredData data) {
        tasks.run("draft", () -> {
            if (cards.findBySha(sha256).isPresent()) return;
            PersonalInfoSanitizer.Result clean;
            try {
                clean = PersonalInfoSanitizer.sanitize(data, lines);
            } catch (RuntimeException e) {
                LOGGER.warn("Monster draft skipped: personal information could not be removed.");
                return;
            }
            logMasked(clean.maskedCount());
            MonsterCard card = MonsterCardGenerator.generate(clean.data(), sha256,
                    MonsterCardGenerator.charCount(clean.lines()), "ANALYZE");
            cards.insertDraft(card);
        });
    }

    /** 保存前の検出と除去。取り除けなければ保存しない（422）。 */
    public PersonalInfoSanitizer.Result sanitizeForSave(ReceiptStructuredData data, List<String> lines) {
        try {
            PersonalInfoSanitizer.Result result = sanitizer.apply(data, lines);
            logMasked(result.maskedCount());
            return result;
        } catch (RuntimeException e) {
            LOGGER.warn("Receipt save rejected: personal information could not be removed.");
            throw new ReceiptException(HttpStatus.UNPROCESSABLE_CONTENT, "PERSONAL_INFO_CHECK_FAILED", PII_FAILED_MESSAGE);
        }
    }

    /**
     * 保存するレシートのカードを作る（DBへの登録と同じタイミングで、トランザクションの外で呼ぶ）。
     * パラメータとイラストは GEMINI_API_MONSTER → GEMINI_API_DEFAULT の順に試して作る。
     * 解析時の下書きは、このカードで上書きする。
     */
    public MonsterCard prepareCardForSave(String sha256, ReceiptStructuredData cleanData, List<String> cleanLines) {
        return generateForSave(sha256, cleanData, cleanLines).card();
    }

    /** {@link #prepareCardForSave} と同じだが、生成AIで作れたかどうかも返す（一括処理で再試行するかの判断用）。 */
    public GeneratedCard generateForSave(String sha256, ReceiptStructuredData cleanData, List<String> cleanLines) {
        List<AiKey> aiKeys = new ArrayList<>(List.of(new AiKey("MONSTER", keys.monster())));
        // GEMINI_API_MONSTER が未設定なら monster() は既定のキーなので、同じキーで2回は呼ばない
        if (!keys.defaultKey().equals(keys.monster())) aiKeys.add(new AiKey("DEFAULT", keys.defaultKey()));
        return generateWithAi(cleanData, sha256, cleanLines, "ANALYZE", aiKeys);
    }

    /** 生成AIの呼び出しに使うキー。name はキーごとの1日の上限を数える名前。 */
    record AiKey(String name, String apiKey) {
    }

    /** 作ったカードと、パラメータ・イラストを生成AIで作れたかどうか（false なら計算式の値・代替イラスト）。 */
    public record GeneratedCard(MonsterCard card, boolean aiParameters, boolean aiIllustration) {
    }

    /**
     * カードを作る。パラメータとイラストは、それぞれ aiKeys を前から順に試して生成AIで作る。
     * どのキーでも作れなければ、計算式のパラメータ・代替イラストのまま。呼び出しは1回ずつキーごとの1日の上限に数える。
     */
    GeneratedCard generateWithAi(ReceiptStructuredData cleanData, String sha256, List<String> cleanLines, String source,
                                 List<AiKey> aiKeys) {
        int charCount = MonsterCardGenerator.charCount(cleanLines);
        MonsterCard base = MonsterCardGenerator.generate(cleanData, sha256, charCount, source);
        if (!settings.aiIllustrationEnabled()) return new GeneratedCard(base, false, false);
        Optional<MonsterCard> withParameters = firstSuccess(aiKeys, "parameters",
                key -> ai.generateParameters(cleanData, charCount, key.apiKey()))
                .map(p -> MonsterCardGenerator.applyParameters(base, p));
        MonsterCard card = withParameters.orElse(base);
        Optional<String> svg = firstSuccess(aiKeys, "illustration", key -> ai.illustrate(card, key.apiKey()));
        return new GeneratedCard(svg.map(card::withSvg).orElse(card), withParameters.isPresent(), svg.isPresent());
    }

    private <T> Optional<T> firstSuccess(List<AiKey> aiKeys, String label, Function<AiKey, Optional<T>> call) {
        for (AiKey key : aiKeys) {
            if (!limiter.tryAcquireGemini(key.name())) continue;
            Optional<T> result = call.apply(key);
            if (result.isPresent()) return result;
            LOGGER.info("Monster card {} could not be generated with key={}.", label, key.name());
        }
        return Optional.empty();
    }

    /** {@link #prepareCardForSave} で作ったカードを有効にする（保存と同じトランザクション内で呼ぶ）。 */
    public void activateForSavedReceipt(String tableName, MonsterCard card) {
        cards.activate(card, tableName);
    }

    /** 保存したレシートのカードを有効にする（保存と同じトランザクション内で呼ぶ）。 */
    public void activateForSavedReceipt(String tableName, String sha256, ReceiptStructuredData cleanData, List<String> cleanLines) {
        MonsterCard card = MonsterCardGenerator.generate(cleanData, sha256, MonsterCardGenerator.charCount(cleanLines), "ANALYZE");
        cards.activate(card, tableName);
    }

    /**
     * 機能追加前に保存されたレシートの再点検（要件定義書 9.4）。
     * 個人情報を取り除いて書き戻し、カードを作る。取り除けないものはカードを作らない（ゲームに出さない）。
     * レシートそのものは自動では削除しない。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillLegacyReceipts() {
        if (!settings.backfillOnStartup()) return;
        try {
            List<String> tables = cards.receiptTablesWithoutCard();
            int created = 0, skipped = 0;
            for (String table : tables) {
                try {
                    tx.executeWithoutResult(status -> {
                        ReceiptStructuredData data = cards.loadReceipt(table).orElse(null);
                        String sha = cards.imageShaOfReceipt(table).orElseThrow();
                        List<String> lines = cards.tableExists(table) ? cards.receiptLines(table) : List.of();
                        PersonalInfoSanitizer.Result clean = PersonalInfoSanitizer.sanitize(data, lines);
                        if (clean.maskedCount() > 0 && clean.data() != null) cards.updateReceiptText(table, clean.data());
                        logMasked(clean.maskedCount());
                        activateForSavedReceipt(table, sha, clean.data(), clean.lines());
                    });
                    created++;
                } catch (RuntimeException e) {
                    skipped++;
                }
            }
            if (!tables.isEmpty()) {
                LOGGER.info("Monster card backfill: created={}, skipped={}", created, skipped);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Monster card backfill failed: exception={}", e.getClass().getName());
        }
    }

    /** 保存されなかった下書きは24時間で破棄する。 */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void discardOldDrafts() {
        int removed = cards.deleteDraftsCreatedBefore(Instant.now().minus(settings.draftTtl()));
        if (removed > 0) LOGGER.info("Discarded {} monster card drafts.", removed);
    }

    void setSanitizerForTest(BiFunction<ReceiptStructuredData, List<String>, PersonalInfoSanitizer.Result> sanitizer) {
        this.sanitizer = sanitizer == null ? PersonalInfoSanitizer::sanitize : sanitizer;
    }

    private static void logMasked(int count) {
        if (count > 0) LOGGER.info("Personal information masked: count={}", count);
    }
}
