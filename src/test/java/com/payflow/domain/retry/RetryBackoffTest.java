package com.payflow.domain.retry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBackoffTest {

    private final RetryBackoff backoff =
            new RetryBackoff(Duration.ofSeconds(1), Duration.ofMinutes(10), 8, new SplittableRandom(42));

    @Test
    void delayNeverExceedsExponentialCeilingOrCap() {
        for (int attempt = 1; attempt <= 40; attempt++) {
            long ceiling = Math.min(600_000L, 1_000L * (1L << Math.min(attempt - 1, 30)));
            for (int i = 0; i < 200; i++) {
                long d = backoff.nextDelay(attempt).toMillis();
                assertTrue(d >= 0 && d <= ceiling, "attempt " + attempt + " delay " + d + " > " + ceiling);
            }
        }
    }

    @Test
    void exhaustedAfterMaxAttempts() {
        assertFalse(backoff.exhausted(7));
        assertTrue(backoff.exhausted(8));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(Duration.ZERO, Duration.ofSeconds(1), 3, new SplittableRandom()));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1), 3, new SplittableRandom()));
    }
}
