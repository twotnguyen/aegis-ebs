package aegis.server;

import aegis.protocol.Envelope;
import aegis.protocol.Priority;
import aegis.protocol.Protocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BoundedWriterQueueTest {

  private static Envelope env(String id) {
    return new Envelope(Protocol.VERSION, "HEARTBEAT", id, null, "2026-11-21T00:00:00Z", Json.obj());
  }

  @Test
  void acceptsUpToCapacityThenRejectsSamePriority() {
    BoundedWriterQueue q = new BoundedWriterQueue(3);
    assertEquals(BoundedWriterQueue.OfferResult.ACCEPTED, q.offer(Priority.INFO, env("1")));
    assertEquals(BoundedWriterQueue.OfferResult.ACCEPTED, q.offer(Priority.INFO, env("2")));
    assertEquals(BoundedWriterQueue.OfferResult.ACCEPTED, q.offer(Priority.INFO, env("3")));
    assertEquals(3, q.size());
    assertEquals(BoundedWriterQueue.OfferResult.REJECTED_SLOW, q.offer(Priority.INFO, env("4")));
    assertEquals(3, q.size());
  }

  @Test
  void higherPriorityEvictsLowestWhenFull() throws InterruptedException {
    BoundedWriterQueue q = new BoundedWriterQueue(2);
    q.offer(Priority.INFO, env("info-1"));
    q.offer(Priority.WARNING, env("warn-1"));
    assertEquals(BoundedWriterQueue.OfferResult.EVICTED_LOWER, q.offer(Priority.CRITICAL, env("crit-1")));
    assertEquals(1, q.evictedCount());
    assertEquals(2, q.size());
    // CRITICAL first, then WARNING (the INFO was evicted)
    assertEquals("crit-1", q.take().messageId());
    assertEquals("warn-1", q.take().messageId());
  }

  @Test
  void fifoWithinTierAndPriorityAcrossTiers() throws InterruptedException {
    BoundedWriterQueue q = new BoundedWriterQueue(10);
    q.offer(Priority.INFO, env("i1"));
    q.offer(Priority.CRITICAL, env("c1"));
    q.offer(Priority.INFO, env("i2"));
    q.offer(Priority.EMERGENCY, env("e1"));
    q.offer(Priority.CRITICAL, env("c2"));
    assertEquals("c1", q.take().messageId());
    assertEquals("c2", q.take().messageId());
    assertEquals("e1", q.take().messageId());
    assertEquals("i1", q.take().messageId());
    assertEquals("i2", q.take().messageId());
  }

  @Test
  void takeBlocksUntilOffer() throws InterruptedException {
    BoundedWriterQueue q = new BoundedWriterQueue(4);
    Thread taker = new Thread(() -> {
      try {
        Envelope e = q.take();
        assertEquals("late", e.messageId());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    });
    taker.start();
    Thread.sleep(50);
    q.offer(Priority.WARNING, env("late"));
    taker.join(2000);
    assertEquals(false, taker.isAlive());
  }

  @Test
  void closeUnblocksTakeAndRejectsOffers() throws InterruptedException {
    BoundedWriterQueue q = new BoundedWriterQueue(4);
    Thread taker = new Thread(() -> {
      try {
        assertNull(q.take());
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      }
    });
    taker.start();
    Thread.sleep(50);
    q.close();
    taker.join(2000);
    assertEquals(false, taker.isAlive());
    assertEquals(BoundedWriterQueue.OfferResult.REJECTED_SLOW, q.offer(Priority.CRITICAL, env("x")));
  }

  @Test
  void defaultCapacityIs256() {
    assertEquals(256, ServerConfig.builder(java.nio.file.Path.of("x.db"),
        java.nio.file.Path.of("x.p12"), "pw").build().writerQueueCapacity());
  }
}
