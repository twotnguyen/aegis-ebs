package aegis.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Durable inbox: write-before-ACK, monotonic revisions, tombstones, restart survival. */
class InboxStoreTest {

  @TempDir Path tmp;
  private InboxStore inbox;
  private final String now = Instant.now().toString();

  @BeforeEach void setUp() { inbox = new InboxStore(tmp.resolve("inbox.db"), "DEV-TEST-01"); }

  private AlertRecord alert(String id, long rev) {
    return new AlertRecord(id, rev, "DLV-" + id + "-" + rev, "INC-1", "B01", "FIRE", "CRITICAL",
        "SIMULATION — Fire Drill rev" + rev, "Training only.", true,
        "Immediate", "Severe", "Observed", Instant.now().plusSeconds(3600).toString(),
        "push-" + id + "-" + rev, null, now, now);
  }

  @Test void storeIsDurableBeforeAckAndPendingUntilMarked() {
    assertEquals(InboxStore.StoreResult.STORED, inbox.store(alert("ALT-1", 1)));
    List<InboxStore.PendingAck> pending = inbox.pendingReceivedAcks();
    assertEquals(1, pending.size(), "committed row must owe ACK_RECEIVED until confirmed sent");
    assertEquals("ALT-1", pending.get(0).alertId());
    assertEquals("push-ALT-1-1", pending.get(0).messageId());
    assertNull(pending.get(0).userId());
    inbox.markAckReceivedSent("ALT-1", 1, Instant.now().toString());
    assertTrue(inbox.pendingReceivedAcks().isEmpty());
  }

  @Test void duplicateAndStaleRevisionsSuppressed() {
    assertEquals(InboxStore.StoreResult.STORED, inbox.store(alert("ALT-1", 2)));
    assertEquals(InboxStore.StoreResult.DUPLICATE, inbox.store(alert("ALT-1", 2)));
    assertEquals(InboxStore.StoreResult.STALE, inbox.store(alert("ALT-1", 1)));
    assertEquals(1, inbox.count());
    assertEquals(2, inbox.find("ALT-1").orElseThrow().revision());
  }

  @Test void newerRevisionResetsAckState() {
    inbox.store(alert("ALT-1", 1));
    inbox.markAckReceivedSent("ALT-1", 1, Instant.now().toString());
    assertTrue(inbox.markUserAck("ALT-1", 1, "USER-1", Instant.now().toString()));
    inbox.markUserAckSent("ALT-1", 1, Instant.now().toString());

    assertEquals(InboxStore.StoreResult.STORED, inbox.store(alert("ALT-1", 2)));
    assertEquals(1, inbox.pendingReceivedAcks().size(), "new revision must be re-ACKed");
    assertEquals(2, inbox.pendingReceivedAcks().get(0).revision());
    assertTrue(inbox.pendingUserAcks().isEmpty());
    assertTrue(inbox.markUserAck("ALT-1", 2, "USER-1", Instant.now().toString()),
        "new revision requires a fresh human acknowledgement");
  }

  @Test void userAckOnlyAdvancesForCurrentNonCancelledRevision() {
    inbox.store(alert("ALT-1", 1));
    assertFalse(inbox.markUserAck("ALT-1", 99, "USER-1", Instant.now().toString()), "wrong revision");
    assertTrue(inbox.markUserAck("ALT-1", 1, "USER-1", Instant.now().toString()));
    assertFalse(inbox.markUserAck("ALT-1", 1, "USER-1", Instant.now().toString()), "already acked");
    inbox.applyCancel("ALT-1", 2, "DEV-TEST-01", "exercise ended", Instant.now().toString());
    inbox.store(alert("ALT-2", 1));
    inbox.applyCancel("ALT-2", 2, "DEV-TEST-01", "exercise ended", Instant.now().toString());
    assertFalse(inbox.markUserAck("ALT-2", 1, "USER-1", Instant.now().toString()), "cancelled alert");
  }

  @Test void cancelTombstonePreventsRedisplayAndStaleCancelIgnored() {
    inbox.store(alert("ALT-1", 1));
    assertTrue(inbox.applyCancel("ALT-1", 2, "DEV-TEST-01", "exercise ended", Instant.now().toString()));
    assertTrue(inbox.isCancelled("ALT-1"));
    assertTrue(inbox.find("ALT-1").isEmpty(), "cancelled alerts are not redisplayed");
    assertTrue(inbox.activeAlerts().isEmpty());
    assertFalse(inbox.applyCancel("ALT-1", 1, "DEV-TEST-01", "stale", Instant.now().toString()));
    assertFalse(inbox.applyCancel("ALT-1", 2, "DEV-TEST-01", "same rev", Instant.now().toString()));
    // A genuinely newer alert revision re-activates (cancel is not permanent).
    assertEquals(InboxStore.StoreResult.STORED, inbox.store(alert("ALT-1", 3)));
    assertFalse(inbox.isCancelled("ALT-1"));
    assertEquals(1, inbox.activeAlerts().size());
  }

  @Test void cancelOfUnknownAlertBlocksLaterStalePush() {
    assertTrue(inbox.applyCancel("ALT-GHOST", 5, "DEV-TEST-01", "pre-emptive", Instant.now().toString()));
    assertEquals(InboxStore.StoreResult.STALE, inbox.store(alert("ALT-GHOST", 1)));
    assertTrue(inbox.activeAlerts().isEmpty());
    assertEquals(Map.of("ALT-GHOST", 5L), inbox.knownRevisions());
  }

  @Test void pendingUserAckSurvivesUntilSent() {
    inbox.store(alert("ALT-1", 1));
    assertTrue(inbox.markUserAck("ALT-1", 1, "USER-1", Instant.now().toString()));
    List<InboxStore.PendingAck> pending = inbox.pendingUserAcks();
    assertEquals(1, pending.size());
    assertEquals("USER-1", pending.get(0).userId());
    inbox.markUserAckSent("ALT-1", 1, Instant.now().toString());
    assertTrue(inbox.pendingUserAcks().isEmpty());
  }

  @Test void stateSurvivesRestart() {
    inbox.store(alert("ALT-1", 1));
    inbox.store(alert("ALT-2", 3));
    inbox.markAckReceivedSent("ALT-2", 3, Instant.now().toString());
    inbox.applyCancel("ALT-3", 2, "DEV-TEST-01", "ended", Instant.now().toString());
    inbox.close();

    InboxStore reopened = new InboxStore(tmp.resolve("inbox.db"), "DEV-TEST-01");
    assertEquals(3, reopened.count());
    assertEquals(2, reopened.activeAlerts().size(), "ALT-1 + ALT-2 active; cancelled ALT-3 hidden");
    assertTrue(reopened.activeAlerts().stream().noneMatch(a -> a.alertId().equals("ALT-3")));
    assertEquals(1, reopened.pendingReceivedAcks().size(), "un-ACKed durable row still owed");
    assertEquals("ALT-1", reopened.pendingReceivedAcks().get(0).alertId());
    assertEquals(Map.of("ALT-1", 1L, "ALT-2", 3L, "ALT-3", 2L), reopened.knownRevisions());
    reopened.close();
  }

  @Test void walModeAndPragmasApplied() throws Exception {
    inbox.store(alert("ALT-1", 1));
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + tmp.resolve("inbox.db"));
         Statement s = c.createStatement()) {
      try (ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
        assertTrue(rs.next());
        assertEquals("wal", rs.getString(1).toLowerCase(), "WAL must persist on the database");
      }
    }
  }

  @Test void concurrentStoresOfSameAlertYieldExactlyOneStored() throws Exception {
    int n = 12;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch gate = new CountDownLatch(1);
    AtomicInteger stored = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      futures.add(pool.submit(() -> {
        try {
          gate.await();
          if (inbox.store(alert("ALT-RACE", 1)) == InboxStore.StoreResult.STORED) stored.incrementAndGet();
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
      }));
    }
    gate.countDown();
    for (Future<?> f : futures) f.get();
    pool.shutdown();
    assertEquals(1, stored.get());
    assertEquals(1, inbox.count());
  }

  @Test void activeAlertsNewestFirst() {
    inbox.store(alert("ALT-OLD", 1));
    inbox.store(new AlertRecord("ALT-NEW", 1, "DLV-NEW", null, null, null, "INFO",
        "SIMULATION — newer", null, true, null, null, null, null,
        "push-new", null, now, Instant.now().plusSeconds(5).toString()));
    List<AlertRecord> active = inbox.activeAlerts();
    assertEquals(List.of("ALT-NEW", "ALT-OLD"), active.stream().map(AlertRecord::alertId).toList());
  }
}
