package aegis.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AckLedgerTest {

    @Test
    void openTracksAllExpectedClients() {
        AckLedger ledger = new AckLedger();
        ledger.open("a1", List.of("c1", "c2", "c3"));
        assertEquals(Set.of("c1", "c2", "c3"), ledger.missing("a1"));
        assertFalse(ledger.complete("a1"));
    }

    @Test
    void ackAdvancesStateUntilComplete() {
        AckLedger ledger = new AckLedger();
        ledger.open("a1", List.of("c1", "c2"));
        assertTrue(ledger.ack("a1", "c1"));
        assertEquals(Set.of("c2"), ledger.missing("a1"));
        assertFalse(ledger.complete("a1"));
        assertTrue(ledger.ack("a1", "c2"));
        assertTrue(ledger.missing("a1").isEmpty());
        assertTrue(ledger.complete("a1"));
    }

    @Test
    void ackIsIdempotent() {
        AckLedger ledger = new AckLedger();
        ledger.open("a1", List.of("c1", "c2"));
        assertTrue(ledger.ack("a1", "c1"));
        assertFalse(ledger.ack("a1", "c1"));
        assertFalse(ledger.ack("a1", "c1"));
        assertEquals(Set.of("c2"), ledger.missing("a1"));
        assertFalse(ledger.complete("a1"));
    }

    @Test
    void ackFromUnexpectedClientIsIgnored() {
        AckLedger ledger = new AckLedger();
        ledger.open("a1", List.of("c1"));
        assertFalse(ledger.ack("a1", "stranger"));
        assertEquals(Set.of("c1"), ledger.missing("a1"));
        assertFalse(ledger.complete("a1"));
    }

    @Test
    void ackOnUnknownAlertReturnsFalse() {
        AckLedger ledger = new AckLedger();
        assertFalse(ledger.ack("nope", "c1"));
    }

    @Test
    void unknownAlertHasEmptyMissingAndIsNotComplete() {
        AckLedger ledger = new AckLedger();
        assertTrue(ledger.missing("nope").isEmpty());
        assertFalse(ledger.complete("nope"));
    }

    @Test
    void emptyExpectedSetCompletesImmediately() {
        AckLedger ledger = new AckLedger();
        ledger.open("a1", List.of());
        assertTrue(ledger.complete("a1"));
        assertTrue(ledger.missing("a1").isEmpty());
    }

    @Test
    void duplicateOpenThrows() {
        AckLedger ledger = new AckLedger();
        ledger.open("a1", List.of("c1"));
        assertThrows(IllegalStateException.class, () -> ledger.open("a1", List.of("c1")));
    }

    @Test
    void missingSetIsUnmodifiable() {
        AckLedger ledger = new AckLedger();
        ledger.open("a1", List.of("c1"));
        Set<String> missing = ledger.missing("a1");
        assertThrows(UnsupportedOperationException.class, () -> missing.add("x"));
    }

    @Test
    void openCopiesInputCollection() {
        AckLedger ledger = new AckLedger();
        List<String> clients = new ArrayList<>(List.of("c1"));
        ledger.open("a1", clients);
        clients.add("c2");
        assertEquals(Set.of("c1"), ledger.missing("a1"));
    }

    @Test
    void openDeduplicatesExpectedClients() {
        AckLedger ledger = new AckLedger();
        ledger.open("a1", List.of("c1", "c1", "c2"));
        assertEquals(Set.of("c1", "c2"), ledger.missing("a1"));
        assertTrue(ledger.ack("a1", "c1"));
        assertFalse(ledger.ack("a1", "c1"));
        assertEquals(Set.of("c2"), ledger.missing("a1"));
    }

    @Test
    void nullArgumentsRejected() {
        AckLedger ledger = new AckLedger();
        assertThrows(NullPointerException.class, () -> ledger.open(null, List.of("c1")));
        assertThrows(NullPointerException.class, () -> ledger.open("a1", null));
        assertThrows(NullPointerException.class, () -> ledger.open("a1", List.of("c1", null)));
        assertThrows(NullPointerException.class, () -> ledger.ack(null, "c1"));
        assertThrows(NullPointerException.class, () -> ledger.ack("a1", null));
        assertThrows(NullPointerException.class, () -> ledger.missing(null));
        assertThrows(NullPointerException.class, () -> ledger.complete(null));
    }

    @Test
    void concurrentAcksCompleteLedger() throws Exception {
        AckLedger ledger = new AckLedger();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 200; i++) ids.add("c" + i);
        ledger.open("a1", ids);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (String id : ids) {
            futures.add(pool.submit(() -> {
                start.await();
                ledger.ack("a1", id);
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        for (Future<?> f : futures) f.get();
        assertTrue(ledger.complete("a1"));
        assertTrue(ledger.missing("a1").isEmpty());
    }
}
