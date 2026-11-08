package com.payflow.domain.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Capped exponential backoff with "full jitter" (AWS Builders' Library):
 * delay = random(0, min(cap, base * 2^attempt)).
 * Jitter spreads retries out so thousands of failed webhooks don't all retry in the same second.
 */
public final class RetryBackoff {

    private final Duration base;
    private final Duration cap;
    private final int maxAttempts;
    private final RandomGenerator random;

    public RetryBackoff(Duration base, Duration cap, int maxAttempts, RandomGenerator random) {
        if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0 || maxAttempts < 1) {
            throw new IllegalArgumentException("invalid backoff configuration");
        }
        this.base = base;
        this.cap = cap;
        this.maxAttempts = maxAttempts;
        this.random = random;
    }

    /** @param attemptsSoFar number of attempts already made (1 after the first failure). */
    public Duration nextDelay(int attemptsSoFar) {
        int exponent = Math.min(Math.max(attemptsSoFar - 1, 0), 30);
        long ceilingMillis = Math.min(cap.toMillis(), base.toMillis() * (1L << exponent));
        return Duration.ofMillis(random.nextLong(ceilingMillis + 1));
    }

    public boolean exhausted(int attemptsSoFar) {
        return attemptsSoFar >= maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
