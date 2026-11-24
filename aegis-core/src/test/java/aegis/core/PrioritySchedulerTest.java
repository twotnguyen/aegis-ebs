package aegis.core;

import aegis.protocol.Envelope;
import aegis.protocol.MessageType;
import aegis.protocol.Priority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PrioritySchedulerTest {

    private static List<String> drain(PriorityScheduler<String> s) {
        List<String> out = new ArrayList<>();
        Optional<String> item;
        while ((item = s.poll()).isPresent()) out.add(item.get());
        return out;
    }

    private static void offerMany(PriorityScheduler<String> s, Priority p, String item, int count) {
        for (int i = 0; i < count; i++) s.offer(p, item);
    }

    @Test
    void strictPriorityWhenNoBurstLimitReached() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        s.offer(Priority.INFO, "i");
        s.offer(Priority.WARNING, "w");
        s.offer(Priority.EMERGENCY, "e");
        s.offer(Priority.CRITICAL, "c");
        assertEquals(List.of("c", "e", "w", "i"), drain(s));
    }

    @Test
    void fifoWithinSameTier() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        s.offer(Priority.CRITICAL, "c1");
        s.offer(Priority.CRITICAL, "c2");
        s.offer(Priority.CRITICAL, "c3");
        assertEquals(List.of("c1", "c2", "c3"), drain(s));
    }

    @Test
    void emptySchedulerReturnsEmpty() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        assertTrue(s.isEmpty());
        assertEquals(0, s.size());
        assertEquals(Optional.empty(), s.poll());
    }

    @Test
    void burstOfEightThenReliefToLowerTier() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        offerMany(s, Priority.CRITICAL, "C", 20);
        offerMany(s, Priority.INFO, "I", 5);
        List<String> expected = new ArrayList<>();
        expected.addAll(Collections.nCopies(8, "C"));
        expected.add("I");
        expected.addAll(Collections.nCopies(8, "C"));
        expected.add("I");
        expected.addAll(Collections.nCopies(4, "C"));
        expected.addAll(Collections.nCopies(3, "I"));
        assertEquals(expected, drain(s));
    }

    @Test
    void reliefRotatesAcrossAllLowerTiers() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        offerMany(s, Priority.CRITICAL, "C", 24);
        offerMany(s, Priority.EMERGENCY, "E", 2);
        offerMany(s, Priority.WARNING, "W", 2);
        offerMany(s, Priority.INFO, "I", 2);
        List<String> expected = new ArrayList<>();
        expected.addAll(Collections.nCopies(8, "C"));
        expected.add("E");
        expected.addAll(Collections.nCopies(8, "C"));
        expected.add("W");
        expected.addAll(Collections.nCopies(8, "C"));
        expected.addAll(List.of("I", "E", "W", "I"));
        assertEquals(expected, drain(s));
    }

    @Test
    void noStarvationUnderFloodOfCritical() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        offerMany(s, Priority.CRITICAL, "C", 100);
        s.offer(Priority.INFO, "I");
        List<String> out = drain(s);
        assertEquals(101, out.size());
        assertEquals(8, out.indexOf("I"), "INFO must be served within the first 9 dispatches");
    }

    @Test
    void everyTierGetsServiceUnderFlood() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        offerMany(s, Priority.CRITICAL, "C", 100);
        s.offer(Priority.EMERGENCY, "E");
        s.offer(Priority.WARNING, "W");
        s.offer(Priority.INFO, "I");
        List<String> out = drain(s);
        assertEquals(103, out.size());
        assertEquals(8, out.indexOf("E"));
        assertEquals(17, out.indexOf("W"));
        assertEquals(26, out.indexOf("I"));
    }

    @Test
    void alternatingHighTiersDoNotStarveInfo() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        s.offer(Priority.CRITICAL, "c");
        s.offer(Priority.EMERGENCY, "e");
        s.offer(Priority.INFO, "i");
        String info = null;
        for (int k = 0; k < 40; k++) {
            String next = s.poll().orElseThrow();
            if ("i".equals(next)) { info = next; break; }
            // Keep replenishing whichever high tier was just consumed: the
            // highest non-empty tier alternates on every poll.
            if ("c".equals(next)) s.offer(Priority.EMERGENCY, "e");
            else s.offer(Priority.CRITICAL, "c");
        }
        assertEquals("i", info, "INFO must be served even when high tiers alternate");
    }

    @Test
    void highTierArrivalPreemptsMidBurst() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        s.offer(Priority.WARNING, "w1");
        s.offer(Priority.WARNING, "w2");
        s.offer(Priority.WARNING, "w3");
        assertEquals("w1", s.poll().orElseThrow());
        s.offer(Priority.CRITICAL, "c1");
        s.offer(Priority.CRITICAL, "c2");
        assertEquals(List.of("c1", "c2", "w2", "w3"), drain(s));
    }

    @Test
    void sizeTracksEnqueuedMinusPolled() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        s.offer(Priority.INFO, "i1");
        s.offer(Priority.CRITICAL, "c1");
        assertEquals(2, s.size());
        s.poll();
        assertEquals(1, s.size());
        assertFalse(s.isEmpty());
        s.poll();
        assertTrue(s.isEmpty());
    }

    @Test
    void nullPriorityOrItemRejected() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        assertThrows(NullPointerException.class, () -> s.offer(null, "x"));
        assertThrows(NullPointerException.class, () -> s.offer(Priority.INFO, null));
    }

    @Test
    void worksWithProtocolEnvelopes() {
        PriorityScheduler<Envelope> s = new PriorityScheduler<>();
        Envelope critical = new Envelope(2, MessageType.ALERT_PUSH.name(), "m-1", "corr-1",
                "2026-11-21T00:00:00Z", null);
        Envelope info = new Envelope(2, MessageType.STATUS_DELTA.name(), "m-2", "corr-2",
                "2026-11-21T00:00:01Z", null);
        s.offer(Priority.INFO, info);
        s.offer(Priority.CRITICAL, critical);
        assertSame(critical, s.poll().orElseThrow());
        assertSame(info, s.poll().orElseThrow());
        assertTrue(s.isEmpty());
    }

    @Test
    void lowerTierServedWhenHigherEmpty() {
        PriorityScheduler<String> s = new PriorityScheduler<>();
        s.offer(Priority.INFO, "i1");
        assertEquals("i1", s.poll().orElseThrow());
        s.offer(Priority.CRITICAL, "c1");
        assertEquals("c1", s.poll().orElseThrow());
        assertTrue(s.isEmpty());
    }

    @Test
    void maxBurstConstantIsEight() {
        assertEquals(8, PriorityScheduler.MAX_BURST);
    }
}
