package aegis.core;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with jitter (pure logic, no sleeping).
 *
 * <p>{@code maxAttempts} counts total attempts including the first one;
 * {@link #shouldRetry(int)} is {@code true} while {@code attemptsSoFar < maxAttempts}.
 * {@link #delayMs(int)} returns {@code baseDelayMs * 2^attemptsSoFar + jitter} with
 * jitter uniform in {@code [0, baseDelayMs)} — matching docs/PROTOCOL.md's
 * "3s/6s/12s + jitter" demo for {@code baseDelayMs=3000}. Delays saturate instead of
 * overflowing for large attempt counts.
 *
 * <p>The injected {@link RandomGenerator} makes tests deterministic; it must be
 * thread-safe if the policy is shared across threads. The default constructor uses
 * {@link ThreadLocalRandom} at call time.
 */
public final class RetryPolicy {

    /** Saturation cap leaving headroom so exponential + jitter cannot overflow. */
    private static final long DELAY_CAP = Long.MAX_VALUE / 2;

    private final int maxAttempts;
    private final long baseDelayMs;
    private final RandomGenerator jitterSource; // null => ThreadLocalRandom at call time

    public RetryPolicy(int maxAttempts, long baseDelayMs) {
        this(maxAttempts, baseDelayMs, null);
    }

    public RetryPolicy(int maxAttempts, long baseDelayMs, RandomGenerator jitterSource) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1: " + maxAttempts);
        }
        if (baseDelayMs < 0) {
            throw new IllegalArgumentException("baseDelayMs must be >= 0: " + baseDelayMs);
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.jitterSource = jitterSource;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public long baseDelayMs() {
        return baseDelayMs;
    }

    /** {@code true} if another attempt is allowed after {@code attemptsSoFar} attempts. */
    public boolean shouldRetry(int attemptsSoFar) {
        requireNonNegative(attemptsSoFar);
        return attemptsSoFar < maxAttempts;
    }

    /** Delay before the next attempt: exponential backoff plus bounded jitter, saturating. */
    public long delayMs(int attemptsSoFar) {
        requireNonNegative(attemptsSoFar);
        long exponential = Math.min(baseDelayMs, DELAY_CAP);
        int shifts = Math.min(attemptsSoFar, 63);
        for (int i = 0; i < shifts && exponential < DELAY_CAP; i++) {
            exponential = exponential > DELAY_CAP / 2 ? DELAY_CAP : exponential * 2;
        }
        long jitter = 0;
        if (baseDelayMs > 0) {
            RandomGenerator rng = jitterSource != null ? jitterSource : ThreadLocalRandom.current();
            jitter = rng.nextLong(baseDelayMs);
        }
        long total = exponential + jitter;
        return total < 0 ? Long.MAX_VALUE : total; // overflow guard for huge baseDelayMs
    }

    private static void requireNonNegative(int attemptsSoFar) {
        if (attemptsSoFar < 0) {
            throw new IllegalArgumentException("attemptsSoFar must be >= 0: " + attemptsSoFar);
        }
    }
}
