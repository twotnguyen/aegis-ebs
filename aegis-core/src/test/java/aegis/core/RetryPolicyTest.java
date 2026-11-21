package aegis.core;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    /** Jitter source that always contributes 0, to pin the exponential part. */
    private static final RandomGenerator ZERO_JITTER = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0L;
        }
    };

    @Test
    void shouldRetryUntilMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3, 3000);
        assertTrue(policy.shouldRetry(0));
        assertTrue(policy.shouldRetry(1));
        assertTrue(policy.shouldRetry(2));
        assertFalse(policy.shouldRetry(3));
        assertFalse(policy.shouldRetry(4));
    }

    @Test
    void singleAttemptMeansNoRetries() {
        RetryPolicy policy = new RetryPolicy(1, 100);
        assertTrue(policy.shouldRetry(0));
        assertFalse(policy.shouldRetry(1));
    }

    @Test
    void invalidConfigRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 3000));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(-1, 3000));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, -1));
    }

    @Test
    void negativeAttemptsSoFarRejected() {
        RetryPolicy policy = new RetryPolicy(3, 3000);
        assertThrows(IllegalArgumentException.class, () -> policy.shouldRetry(-1));
        assertThrows(IllegalArgumentException.class, () -> policy.delayMs(-1));
    }

    @Test
    void exponentialBackoffMatchesProtocolDemo() {
        // docs/PROTOCOL.md: retry demo 3s/6s/12s + jitter, max 3 retries then FAILED.
        RetryPolicy policy = new RetryPolicy(4, 3000, ZERO_JITTER);
        assertEquals(3000, policy.delayMs(0));
        assertEquals(6000, policy.delayMs(1));
        assertEquals(12000, policy.delayMs(2));
    }

    @Test
    void jitterStaysWithinBounds() {
        RetryPolicy policy = new RetryPolicy(10, 3000, new Random(42));
        for (int n = 0; n <= 5; n++) {
            long base = 3000L << n;
            for (int sample = 0; sample < 100; sample++) {
                long delay = policy.delayMs(n);
                assertTrue(delay >= base, "delay " + delay + " below exponential base " + base);
                assertTrue(delay < base + 3000, "delay " + delay + " exceeds base+jitter bound");
            }
        }
    }

    @Test
    void defaultConstructorUsesRandomJitter() {
        RetryPolicy policy = new RetryPolicy(5, 100);
        for (int n = 0; n <= 3; n++) {
            long base = 100L << n;
            long delay = policy.delayMs(n);
            assertTrue(delay >= base && delay < base + 100);
        }
    }

    @Test
    void sameSeedProducesSameDelays() {
        RetryPolicy a = new RetryPolicy(6, 3000, new Random(7));
        RetryPolicy b = new RetryPolicy(6, 3000, new Random(7));
        for (int n = 0; n < 6; n++) {
            assertEquals(a.delayMs(n), b.delayMs(n));
        }
    }

    @Test
    void zeroBaseDelayGivesZero() {
        RetryPolicy policy = new RetryPolicy(3, 0, new Random(1));
        assertEquals(0, policy.delayMs(0));
        assertEquals(0, policy.delayMs(1));
        assertEquals(0, policy.delayMs(2));
    }

    @Test
    void hugeAttemptCountSaturatesWithoutOverflow() {
        // Exponential part saturates; with zero jitter the delay is stable across huge counts.
        RetryPolicy pinned = new RetryPolicy(Integer.MAX_VALUE, 3000, ZERO_JITTER);
        long delay = pinned.delayMs(1000);
        assertTrue(delay > 0);
        assertTrue(delay >= 3000L * (1L << 20));
        assertEquals(delay, pinned.delayMs(63));
        assertEquals(delay, pinned.delayMs(2000));
        // Below the cap the backoff is still exact: 3000 * 2^50 fits in a long.
        assertEquals(3000L << 50, pinned.delayMs(50));
        // With real jitter the saturated delay stays in [cap, cap + base) — never overflowed.
        RetryPolicy jittered = new RetryPolicy(Integer.MAX_VALUE, 3000, new Random(3));
        for (int n : new int[]{63, 1000, 2000}) {
            long d = jittered.delayMs(n);
            assertTrue(d >= delay && d < delay + 3000);
        }
    }

    @Test
    void accessorsExposeConfig() {
        RetryPolicy policy = new RetryPolicy(3, 3000);
        assertEquals(3, policy.maxAttempts());
        assertEquals(3000, policy.baseDelayMs());
    }
}
