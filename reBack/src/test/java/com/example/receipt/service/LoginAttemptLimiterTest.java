package com.example.receipt.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock, 100);

    @Test
    void locksAfterMaxFailuresAndUnlocksAfterLockDuration() {
        fail("admin");
        fail("admin");
        assertThat(limiter.isLocked("admin")).isFalse();

        fail("admin");
        assertThat(limiter.isLocked("admin")).isTrue();
        assertThat(limiter.tryBegin("admin")).isNull();
        assertThat(limiter.tryBegin("other")).isNotNull();

        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.isLocked("admin")).isFalse();
        assertThat(limiter.tryBegin("admin")).isNotNull();
    }

    @Test
    void successResetsFailureCount() {
        fail("admin");
        fail("admin");
        limiter.tryBegin("admin").succeeded();
        fail("admin");
        fail("admin");

        assertThat(limiter.isLocked("admin")).isFalse();
    }

    @Test
    void oldFailuresAreForgottenAfterWindow() {
        fail("admin");
        fail("admin");
        clock.advance(Duration.ofMinutes(15));
        fail("admin");

        assertThat(limiter.isLocked("admin")).isFalse();
    }

    @Test
    void usernameIsComparedIgnoringCaseAndSurroundingSpaces() {
        fail("Admin");
        fail(" admin ");
        fail("ADMIN");

        assertThat(limiter.isLocked("admin")).isTrue();
    }

    @Test
    void attemptsInProgressCountTowardTheLimit() {
        LoginAttemptLimiter.Attempt first = limiter.tryBegin("admin");
        LoginAttemptLimiter.Attempt second = limiter.tryBegin("admin");
        LoginAttemptLimiter.Attempt third = limiter.tryBegin("admin");

        assertThat(limiter.tryBegin("admin")).isNull();

        first.failed();
        second.failed();
        third.failed();
        assertThat(limiter.isLocked("admin")).isTrue();
    }

    @Test
    void attemptClosedWithoutResultReleasesItsSlotWithoutCountingAFailure() {
        LoginAttemptLimiter.Attempt aborted = limiter.tryBegin("admin");
        aborted.close();
        aborted.failed();

        fail("admin");
        fail("admin");
        assertThat(limiter.isLocked("admin")).isFalse();
        assertThat(limiter.tryBegin("admin")).isNotNull();
    }

    @Test
    void concurrentAttemptsNeverExceedTheLimit() throws Exception {
        int threads = 20;
        CountDownLatch allStarted = new CountDownLatch(threads);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    LoginAttemptLimiter.Attempt attempt = limiter.tryBegin("admin");
                    allStarted.countDown();
                    if (attempt == null) {
                        return false;
                    }
                    // パスワード照合中を模して、全スレッドが枠の確保を終えるまで結果を記録しない
                    release.await(5, TimeUnit.SECONDS);
                    attempt.failed();
                    return true;
                }));
            }
            assertThat(allStarted.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(3);
            assertThat(limiter.isLocked("admin")).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void trackedUsernamesAreBoundedAndInProgressAttemptsAreKept() {
        LoginAttemptLimiter small = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock, 3);
        LoginAttemptLimiter.Attempt inProgress = small.tryBegin("admin");
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(1));
            small.tryBegin("spray-" + i).failed();
        }

        assertThat(small.trackedUsernames()).isLessThanOrEqualTo(3);
        inProgress.failed();
        inProgress.close();
        assertThat(small.trackedUsernames()).isLessThanOrEqualTo(3);
    }

    private void fail(String username) {
        LoginAttemptLimiter.Attempt attempt = limiter.tryBegin(username);
        assertThat(attempt).isNotNull();
        attempt.failed();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-05T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
