package aegis.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRegistryTest {

  @Test
  void nearestRankPercentilesOverKnownWindow() {
    MetricsRegistry m = new MetricsRegistry();
    for (long i = 1; i <= 100; i++) m.recordLatency(i);
    assertEquals(50, m.p50());
    assertEquals(95, m.p95());
    assertEquals(99, m.p99());
  }

  @Test
  void emptyWindowYieldsZero() {
    MetricsRegistry m = new MetricsRegistry();
    assertEquals(0, m.p50());
    assertEquals(0, m.p95());
    assertEquals(0, m.p99());
  }

  @Test
  void singleSampleIsEveryPercentile() {
    MetricsRegistry m = new MetricsRegistry();
    m.recordLatency(42);
    assertEquals(42, m.p50());
    assertEquals(42, m.p95());
    assertEquals(42, m.p99());
  }

  @Test
  void reservoirIsBounded() {
    MetricsRegistry m = new MetricsRegistry();
    for (long i = 0; i < MetricsRegistry.MAX_SAMPLES + 500; i++) m.recordLatency(i);
    assertEquals(MetricsRegistry.MAX_SAMPLES, m.sampleCount());
    // oldest samples evicted: window is the most recent MAX_SAMPLES values
    assertEquals(MetricsRegistry.MAX_SAMPLES + 500 - 1, m.percentile(100));
  }

  @Test
  void countersAreMonotonic() {
    MetricsRegistry m = new MetricsRegistry();
    long prevRetry = m.retryCount();
    for (int i = 0; i < 5; i++) {
      m.incRetries();
      m.incPublished();
      m.incAckReceived();
      m.incAckUser();
      m.incFailed();
      assertTrue(m.retryCount() >= prevRetry);
      prevRetry = m.retryCount();
    }
    assertEquals(5, m.retryCount());
    assertEquals(5, m.publishedCount());
    assertEquals(5, m.ackReceivedCount());
    assertEquals(5, m.ackUserCount());
    assertEquals(5, m.failedCount());
  }

  @Test
  void negativeLatencyIgnored() {
    MetricsRegistry m = new MetricsRegistry();
    m.recordLatency(-5);
    assertEquals(0, m.sampleCount());
  }
}
