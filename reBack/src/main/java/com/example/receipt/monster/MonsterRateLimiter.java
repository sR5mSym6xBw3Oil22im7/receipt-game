package com.example.receipt.monster;

import com.example.receipt.exception.ReceiptException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * IPごとの回数制限と、Gemini呼び出しのキーごとの1日の上限（要件定義書 13章）。
 * IPはプロセスごとの乱数を混ぜたハッシュだけを、メモリ内に保持する。
 */
@Component
public class MonsterRateLimiter {
    public enum Action {
        CREATE_ROOM(5, Duration.ofMinutes(10)),
        JOIN_ROOM(10, Duration.ofMinutes(1)),
        IMAGE_CARD(10, Duration.ofDays(1));

        final int limit;
        final Duration window;

        Action(int limit, Duration window) {
            this.limit = limit;
            this.window = window;
        }
    }

    private static final int MAX_TRACKED_KEYS = 20_000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private final MonsterSettings settings;
    private final Clock clock;
    private final byte[] salt = new byte[16];
    private final Map<String, Deque<Instant>> hits = new HashMap<>();
    private final Map<String, Integer> geminiCalls = new HashMap<>();
    private LocalDate geminiDay;

    @Autowired
    public MonsterRateLimiter(MonsterSettings settings) {
        this(settings, Clock.systemUTC());
    }

    MonsterRateLimiter(MonsterSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        new SecureRandom().nextBytes(salt);
    }

    /** 上限を超えていれば 429 を投げる。 */
    public void check(Action action, HttpServletRequest request) {
        if (!tryAcquire(action, clientIpHash(request))) {
            throw new ReceiptException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", switch (action) {
                case CREATE_ROOM -> "ルームの作成回数が上限に達しました。しばらく待ってから試してください。";
                case JOIN_ROOM -> "参加の試行回数が上限に達しました。1分ほど待ってから試してください。";
                case IMAGE_CARD -> "画像からのカード作成は、本日の上限に達しました。既存のレシートから選んで対戦できます。";
            });
        }
    }

    synchronized boolean tryAcquire(Action action, String ipHash) {
        Instant now = clock.instant();
        String key = action.name() + ":" + ipHash;
        Deque<Instant> times = hits.get(key);
        if (times == null) {
            if (hits.size() >= MAX_TRACKED_KEYS) prune(now);
            times = new ArrayDeque<>();
            hits.put(key, times);
        }
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(action.window))) times.pollFirst();
        if (times.size() >= action.limit) return false;
        times.addLast(now);
        return true;
    }

    /** Gemini呼び出しの1日あたりの全体上限（キーごと）。超えていれば false。 */
    public synchronized boolean tryAcquireGemini(String keyName) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
        if (!today.equals(geminiDay)) {
            geminiDay = today;
            geminiCalls.clear();
        }
        int used = geminiCalls.getOrDefault(keyName, 0);
        if (used >= settings.geminiDailyLimitPerKey()) return false;
        geminiCalls.put(keyName, used + 1);
        return true;
    }

    private void prune(Instant now) {
        hits.entrySet().removeIf(entry -> entry.getValue().isEmpty()
                || entry.getValue().peekLast().isBefore(now.minus(Duration.ofDays(1))));
    }

    String clientIpHash(HttpServletRequest request) {
        return hash(clientIp(request));
    }

    String clientIp(HttpServletRequest request) {
        if (settings.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // 末尾はプロキシが付けた値。先頭側は利用者が偽装できるため使わない。
                String[] parts = forwarded.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty()) return last;
            }
        }
        return request.getRemoteAddr();
    }

    private String hash(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return HexFormat.of().formatHex(digest.digest(String.valueOf(ip).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
