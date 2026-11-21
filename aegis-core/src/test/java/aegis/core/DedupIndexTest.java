package aegis.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DedupIndexTest {

    @Test
    void firstSightIsNotDuplicate() {
        DedupIndex index = new DedupIndex();
        assertFalse(index.seen("a1", 1, "d1"));
    }

    @Test
    void exactDuplicateIsSuppressed() {
        DedupIndex index = new DedupIndex();
        assertFalse(index.seen("a1", 1, "d1"));
        assertTrue(index.seen("a1", 1, "d1"));
    }

    @Test
    void repeatedCallsAreIdempotent() {
        DedupIndex index = new DedupIndex();
        assertFalse(index.seen("a1", 1, "d1"));
        for (int i = 0; i < 5; i++) {
            assertTrue(index.seen("a1", 1, "d1"));
        }
    }

    @Test
    void olderRevisionIsSuppressed() {
        DedupIndex index = new DedupIndex();
        assertFalse(index.seen("a1", 5, "d1"));
        assertTrue(index.seen("a1", 4, "d1"));
        assertTrue(index.seen("a1", 5, "d1"));
    }

    @Test
    void newerRevisionIsDeliveredOnce() {
        DedupIndex index = new DedupIndex();
        assertFalse(index.seen("a1", 5, "d1"));
        assertFalse(index.seen("a1", 6, "d1"));
        assertTrue(index.seen("a1", 6, "d1"));
        assertTrue(index.seen("a1", 5, "d1"));
    }

    @Test
    void devicesAreIndependent() {
        DedupIndex index = new DedupIndex();
        assertFalse(index.seen("a1", 1, "d1"));
        assertFalse(index.seen("a1", 1, "d2"));
        assertTrue(index.seen("a1", 1, "d1"));
        assertTrue(index.seen("a1", 1, "d2"));
    }

    @Test
    void alertsAreIndependent() {
        DedupIndex index = new DedupIndex();
        assertFalse(index.seen("a1", 1, "d1"));
        assertFalse(index.seen("a2", 1, "d1"));
        assertTrue(index.seen("a1", 1, "d1"));
        assertTrue(index.seen("a2", 1, "d1"));
    }

    @Test
    void nullArgumentsRejected() {
        DedupIndex index = new DedupIndex();
        assertThrows(NullPointerException.class, () -> index.seen(null, 1, "d1"));
        assertThrows(NullPointerException.class, () -> index.seen("a1", 1, null));
    }

    @Test
    void concurrentFirstSightWinsExactlyOnce() throws Exception {
        DedupIndex index = new DedupIndex();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                barrier.await();
                return index.seen("a1", 1, "d1");
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        long firstSights = 0;
        for (Future<Boolean> f : results) {
            if (!f.get()) firstSights++;
        }
        assertEquals(1, firstSights);
    }
}
