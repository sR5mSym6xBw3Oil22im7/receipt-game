package com.example.receipt.monster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** モンスターレシート対戦の設定（application.yml の app.monster.*）。 */
@Component
public class MonsterSettings {
    private final Duration waitTimeout;
    private final Duration roomTtl;
    private final Duration unresponsiveAfter;
    private final Duration draftTtl;
    private final boolean aiIllustrationEnabled;
    private final boolean trustForwardedFor;
    private final int geminiDailyLimitPerKey;
    private final boolean backfillOnStartup;
    private final String model;

    public MonsterSettings(
            @Value("${app.monster.wait-seconds:90}") long waitSeconds,
            @Value("${app.monster.room-ttl-minutes:120}") long roomTtlMinutes,
            @Value("${app.monster.unresponsive-seconds:60}") long unresponsiveSeconds,
            @Value("${app.monster.draft-ttl-hours:24}") long draftTtlHours,
            @Value("${app.monster.ai-illustration-enabled:false}") boolean aiIllustrationEnabled,
            @Value("${app.monster.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${app.monster.gemini-daily-limit-per-key:200}") int geminiDailyLimitPerKey,
            @Value("${app.monster.backfill-on-startup:true}") boolean backfillOnStartup,
            @Value("${gemini.monster-model:${gemini.receipt-model:gemini-3.5-flash-lite}}") String model) {
        this.waitTimeout = Duration.ofSeconds(waitSeconds);
        this.roomTtl = Duration.ofMinutes(roomTtlMinutes);
        this.unresponsiveAfter = Duration.ofSeconds(unresponsiveSeconds);
        this.draftTtl = Duration.ofHours(draftTtlHours);
        this.aiIllustrationEnabled = aiIllustrationEnabled;
        this.trustForwardedFor = trustForwardedFor;
        this.geminiDailyLimitPerKey = geminiDailyLimitPerKey;
        this.backfillOnStartup = backfillOnStartup;
        this.model = model;
    }

    public Duration waitTimeout() { return waitTimeout; }
    public Duration roomTtl() { return roomTtl; }
    public Duration unresponsiveAfter() { return unresponsiveAfter; }
    public Duration draftTtl() { return draftTtl; }
    public boolean aiIllustrationEnabled() { return aiIllustrationEnabled; }
    public boolean trustForwardedFor() { return trustForwardedFor; }
    public int geminiDailyLimitPerKey() { return geminiDailyLimitPerKey; }
    public boolean backfillOnStartup() { return backfillOnStartup; }
    public String model() { return model; }
}
