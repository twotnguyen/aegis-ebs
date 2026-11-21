package aegis.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic counters + bounded latency reservoir with nearest-rank percentiles.
 * Counters only ever increase (EBSP: ACK only advances state); latency samples
 * are kept in a bounded FIFO window so p50/p95/p99 reflect recent deliveries.
 * Thread-safe.
 */
public final class MetricsRegistry {
  /** Reservoir bound: recent 4096 end-to-end latency samples (ms). */
  public static final int MAX_SAMPLES = 4096;

  private final ArrayDeque<Long> latencies = new ArrayDeque<>();
  private final AtomicLong retryCount = new AtomicLong();
  private final AtomicLong publishedCount = new AtomicLong();
  private final AtomicLong ackReceivedCount = new AtomicLong();
  private final AtomicLong ackUserCount = new AtomicLong();
  private final AtomicLong failedCount = new AtomicLong();
  private final AtomicLong slowClientDrops = new AtomicLong();
  private final AtomicLong slowClientDisconnects = new AtomicLong();

  /** Records one end-to-end delivery latency sample (publish -> ACK_RECEIVED), ms. */
  public void recordLatency(long millis) {
    if (millis < 0) return;
    synchronized (latencies) {
      latencies.addLast(millis);
      if (latencies.size() > MAX_SAMPLES) latencies.removeFirst();
    }
  }

  /** Nearest-rank percentile over the current window; 0 when no samples. */
  public long percentile(double p) {
    if (p < 0 || p > 100) throw new IllegalArgumentException("percentile in 0..100: " + p);
    List<Long> sorted;
    synchronized (latencies) {
      if (latencies.isEmpty()) return 0;
      sorted = new ArrayList<>(latencies);
    }
    Collections.sort(sorted);
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    int index = Math.min(Math.max(rank, 1), sorted.size()) - 1;
    return sorted.get(index);
  }

  public long p50() { return percentile(50); }
  public long p95() { return percentile(95); }
  public long p99() { return percentile(99); }

  public void incRetries() { retryCount.incrementAndGet(); }
  public void incPublished() { publishedCount.incrementAndGet(); }
  public void incAckReceived() { ackReceivedCount.incrementAndGet(); }
  public void incAckUser() { ackUserCount.incrementAndGet(); }
  public void incFailed() { failedCount.incrementAndGet(); }
  public void incSlowClientDrops() { slowClientDrops.incrementAndGet(); }
  public void incSlowClientDisconnects() { slowClientDisconnects.incrementAndGet(); }

  public long retryCount() { return retryCount.get(); }
  public long publishedCount() { return publishedCount.get(); }
  public long ackReceivedCount() { return ackReceivedCount.get(); }
  public long ackUserCount() { return ackUserCount.get(); }
  public long failedCount() { return failedCount.get(); }
  public long slowClientDrops() { return slowClientDrops.get(); }
  public long slowClientDisconnects() { return slowClientDisconnects.get(); }
  public int sampleCount() { synchronized (latencies) { return latencies.size(); } }
}
