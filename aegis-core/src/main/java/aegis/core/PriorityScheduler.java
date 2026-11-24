package aegis.core;

import aegis.protocol.Priority;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Strict-priority dispatcher with bounded bursts so lower tiers never starve.
 *
 * <p>Tiers: CRITICAL &gt; EMERGENCY &gt; WARNING &gt; INFO (protocol enum ordinals).
 * FIFO within a tier. Fairness rule: at most {@link #MAX_BURST} (8) consecutive dispatches
 * from the highest non-empty tier, then one dispatch from a lower non-empty tier, rotating
 * through all lower tiers in descending order. With k lower tiers non-empty, each receives
 * service at least once per (8+1)*k dispatches while the flood lasts — no starvation.
 * When only one tier is non-empty it is served continuously. A newly arriving higher tier
 * preempts immediately. Thread-safe; pure logic, no blocking.
 *
 * @param <T> item type, e.g. {@code aegis.protocol.Envelope}
 */
public final class PriorityScheduler<T> {

    /** Maximum consecutive dispatches from the highest tier before lower tiers are considered. */
    public static final int MAX_BURST = 8;

    private static final int TIERS = Priority.values().length;

    private final ArrayDeque<T>[] queues;
    private int burstCount = 0;
    private int lastReliefTier = -1;
    private int size = 0;

    @SuppressWarnings("unchecked")
    public PriorityScheduler() {
        queues = new ArrayDeque[TIERS];
        for (int i = 0; i < TIERS; i++) {
            queues[i] = new ArrayDeque<>();
        }
    }

    public synchronized void offer(Priority priority, T item) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(item, "item");
        queues[priority.ordinal()].addLast(item);
        size++;
    }

    /** Next item under the priority+fairness policy, or empty when the scheduler is drained. */
    public synchronized Optional<T> poll() {
        int highest = highestNonEmpty();
        if (highest < 0) {
            return Optional.empty();
        }
        int relief = pickReliefTier(highest);
        if (relief >= 0 && burstCount >= MAX_BURST) {
            lastReliefTier = relief;
            burstCount = 0;
            size--;
            return Optional.of(queues[relief].pollFirst());
        }
        if (relief < 0) {
            // no lower tier is waiting: the high-tier burst budget is irrelevant
            burstCount = 0;
        }
        burstCount++;
        size--;
        return Optional.of(queues[highest].pollFirst());
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized int size() {
        return size;
    }

    private int highestNonEmpty() {
        for (int tier = TIERS - 1; tier >= 0; tier--) {
            if (!queues[tier].isEmpty()) {
                return tier;
            }
        }
        return -1;
    }

    /** Rotates through non-empty tiers below {@code highest}, descending, wrapping around. */
    private int pickReliefTier(int highest) {
        List<Integer> lower = new ArrayList<>();
        for (int tier = highest - 1; tier >= 0; tier--) {
            if (!queues[tier].isEmpty()) {
                lower.add(tier);
            }
        }
        if (lower.isEmpty()) {
            return -1;
        }
        int index = lower.indexOf(lastReliefTier); // -1 when last relief tier drained
        return lower.get((index + 1) % lower.size());
    }
}
