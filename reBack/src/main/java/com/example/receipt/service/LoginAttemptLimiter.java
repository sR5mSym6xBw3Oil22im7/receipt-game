package com.example.receipt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ユーザーIDごとにログイン試行を数え、上限に達したら一定時間ログインを受け付けない。
 * 管理者パスワードの総当たりを遅らせるためのもので、状態はプロセス内メモリにのみ保持する。
 *
 * パスワード照合の前に {@link #tryBegin(String)} で試行枠を確保するため、
 * 同時に送られた要求も「失敗回数＋照合中の件数」で上限に数えられる。
 */
@Component
public class LoginAttemptLimiter {
    static final int DEFAULT_MAX_TRACKED_USERNAMES = 10_000;

    private final int maxFailures;
    private final Duration lockDuration;
    private final Clock clock;
    private final int maxTrackedUsernames;
    private final Map<String, State> states = new HashMap<>();

    @Autowired
    public LoginAttemptLimiter(@Value("${app.auth.login-max-failures:5}") int maxFailures,
                               @Value("${app.auth.login-lock-minutes:15}") long lockMinutes) {
        this(maxFailures, Duration.ofMinutes(lockMinutes), Clock.systemUTC(), DEFAULT_MAX_TRACKED_USERNAMES);
    }

    LoginAttemptLimiter(int maxFailures, Duration lockDuration, Clock clock, int maxTrackedUsernames) {
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
        this.clock = clock;
        this.maxTrackedUsernames = maxTrackedUsernames;
    }

    /**
     * 試行枠を確保する。ロック中、または失敗回数と照合中の件数の合計が上限に達している場合はnullを返す。
     * 確保した枠は、照合結果に応じて {@link Attempt#succeeded()} か {@link Attempt#failed()} を呼んで閉じる。
     */
    public synchronized Attempt tryBegin(String username) {
        String key = key(username);
        Instant now = clock.instant();
        State state = states.get(key);
        if (state != null && state.isExpired(now, lockDuration)) {
            states.remove(key);
            state = null;
        }
        if (state == null) {
            makeRoom(now);
            state = new State(now);
            states.put(key, state);
        }
        if (state.isLocked(now) || state.failures + state.inFlight >= maxFailures) {
            return null;
        }
        state.inFlight++;
        state.lastActivity = now;
        return new Attempt(key);
    }

    synchronized boolean isLocked(String username) {
        State state = states.get(key(username));
        return state != null && state.isLocked(clock.instant());
    }

    synchronized int trackedUsernames() {
        return states.size();
    }

    private synchronized void finish(String key, Outcome outcome) {
        State state = states.get(key);
        if (state == null) {
            return;
        }
        Instant now = clock.instant();
        state.inFlight--;
        state.lastActivity = now;
        if (outcome == Outcome.SUCCEEDED) {
            state.failures = 0;
            state.lockedUntil = null;
        } else if (outcome == Outcome.FAILED) {
            state.failures++;
            if (state.failures >= maxFailures) {
                state.lockedUntil = now.plus(lockDuration);
                state.failures = 0;
            }
        }
        if (state.inFlight == 0 && state.failures == 0 && !state.isLocked(now)) {
            states.remove(key);
        }
    }

    // 上限に達したら期限切れを捨て、それでも足りなければ照合中でない最も古い記録を捨てる
    private void makeRoom(Instant now) {
        if (states.size() < maxTrackedUsernames) {
            return;
        }
        states.values().removeIf(state -> state.isExpired(now, lockDuration));
        while (states.size() >= maxTrackedUsernames) {
            String oldest = states.entrySet().stream()
                    .filter(entry -> entry.getValue().inFlight == 0)
                    .min(Comparator.comparing(entry -> entry.getValue().lastActivity))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            states.remove(oldest);
        }
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private enum Outcome { SUCCEEDED, FAILED, ABORTED }

    /** 1回分のログイン試行。結果を記録しないまま閉じた場合は、枠を返すだけで失敗回数には数えない。 */
    public final class Attempt implements AutoCloseable {
        private final String key;
        private boolean finished;

        private Attempt(String key) {
            this.key = key;
        }

        public void succeeded() {
            finishOnce(Outcome.SUCCEEDED);
        }

        public void failed() {
            finishOnce(Outcome.FAILED);
        }

        @Override
        public void close() {
            finishOnce(Outcome.ABORTED);
        }

        private void finishOnce(Outcome outcome) {
            if (!finished) {
                finished = true;
                finish(key, outcome);
            }
        }
    }

    private static final class State {
        int failures;
        int inFlight;
        Instant lastActivity;
        Instant lockedUntil;

        State(Instant now) {
            this.lastActivity = now;
        }

        boolean isLocked(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        // 照合中でなく、ロックも最後の試行から一定時間も過ぎていれば、失敗回数を数え直す
        boolean isExpired(Instant now, Duration window) {
            return inFlight == 0 && !isLocked(now) && !now.isBefore(lastActivity.plus(window));
        }
    }
}
