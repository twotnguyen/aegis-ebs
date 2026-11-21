package aegis.server;

import aegis.protocol.Envelope;
import aegis.protocol.Priority;

import java.util.TreeSet;

/**
 * Per-session bounded writer queue (default capacity 256) with a deterministic
 * slow-client policy:
 *
 * <ul>
 *   <li>Queue not full -> {@link OfferResult#ACCEPTED}.</li>
 *   <li>Full and the incoming frame outranks the lowest-priority queued frame ->
 *       evict that frame ({@link OfferResult#EVICTED_LOWER}); higher priority
 *       traffic (e.g. CRITICAL control frames) is never blocked by INFO floods.</li>
 *   <li>Full and nothing lower can be evicted -> {@link OfferResult#REJECTED_SLOW};
 *       the caller disconnects the slow client (it is not draining its socket).</li>
 * </ul>
 *
 * FIFO within a priority tier. {@link #take()} blocks until a frame is available
 * or the queue is closed (returns {@code null} after close = writer shutdown).
 */
public final class BoundedWriterQueue {

  public enum OfferResult { ACCEPTED, EVICTED_LOWER, REJECTED_SLOW }

  private record Item(Priority priority, long seq, Envelope envelope) {}

  private final int capacity;
  private final TreeSet<Item> items = new TreeSet<>(
      java.util.Comparator.<Item>comparingInt(i -> -i.priority().ordinal())
          .thenComparingLong(Item::seq));
  private long seq = 0;
  private long evicted = 0;
  private boolean closed = false;

  public BoundedWriterQueue(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("capacity >= 1: " + capacity);
    this.capacity = capacity;
  }

  public synchronized OfferResult offer(Priority priority, Envelope envelope) {
    if (closed) return OfferResult.REJECTED_SLOW;
    if (items.size() < capacity) {
      items.add(new Item(priority, seq++, envelope));
      notifyAll();
      return OfferResult.ACCEPTED;
    }
    Item lowest = items.last();
    if (priority.ordinal() > lowest.priority().ordinal()) {
      items.remove(lowest);
      evicted++;
      items.add(new Item(priority, seq++, envelope));
      notifyAll();
      return OfferResult.EVICTED_LOWER;
    }
    return OfferResult.REJECTED_SLOW;
  }

  /** Blocks for the next frame; {@code null} once closed and drained. */
  public synchronized Envelope take() throws InterruptedException {
    while (items.isEmpty() && !closed) wait();
    if (items.isEmpty()) return null;
    Item first = items.first();
    items.remove(first);
    return first.envelope();
  }

  public synchronized void close() {
    closed = true;
    notifyAll();
  }

  public synchronized int size() { return items.size(); }
  public synchronized long evictedCount() { return evicted; }
  public synchronized boolean isClosed() { return closed; }
  public int capacity() { return capacity; }
}
