package aegis.server;

import aegis.core.AckLedger;
import aegis.core.DedupIndex;
import aegis.core.PriorityScheduler;
import aegis.core.RetryPolicy;
import aegis.persistence.Alert;
import aegis.persistence.AlertRepo;
import aegis.persistence.AlertRevision;
import aegis.persistence.AlertState;
import aegis.persistence.Delivery;
import aegis.persistence.DeliveryEnqueuer;
import aegis.persistence.DeliveryRepo;
import aegis.persistence.DeliveryState;
import aegis.persistence.TransactionalPublisher;
import aegis.protocol.Envelope;
import aegis.protocol.Priority;
import aegis.protocol.Protocol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Priority fanout + retry engine. Implements {@link DeliveryEnqueuer} so the
 * TransactionalPublisher hands over durable PENDING deliveries only AFTER
 * commit; {@link aegis.persistence.RecoveryScanner} rebuilds the same queue
 * after a crash via {@link #recoverEnqueue(List)}.
 *
 * <p>Dispatch order: global {@link PriorityScheduler} (CRITICAL &gt; EMERGENCY
 * &gt; WARNING &gt; INFO, bounded bursts) -&gt; per-session bounded writer queue
 * (256) with the slow-client policy. Retries follow {@link RetryPolicy}
 * (3s/6s/12s + jitter, max 3 retries then FAILED): {@code shouldRetry} is fed
 * the number of retries already consumed, so the initial send plus 3 retries
 * are allowed. Each transport attempt uses a fresh messageId and is recorded
 * in delivery_attempts; ACK only advances state (DeliveryRepo rank guard).
 *
 * <p>{@link DedupIndex} gates FIRST dispatch of a fresh delivery: an equal or
 * newer revision already dispatched to the same device suppresses stale
 * pushes (e.g. a late revision-1 retry after revision 2 went out). Retries and
 * recovered deliveries bypass the gate — they continue an owned lifecycle.
 */
public final class FanoutEngine implements DeliveryEnqueuer {
  private static final Logger LOG = LoggerFactory.getLogger(FanoutEngine.class);

  private record Tracked(String deliveryId, Alert alert, AlertRevision revision, boolean fresh) {}

  private record Dispatch(String deliveryId) {}

  private final AlertRepo alerts;
  private final DeliveryRepo deliveries;
  private final SessionRegistry sessions;
  private final RetryPolicy retryPolicy;
  private final ScheduledExecutorService timers;
  private final Clock clock;
  private final MetricsRegistry metrics;
  private final AuditService audit;
  private final StatusNotifier status;

  private final PriorityScheduler<Dispatch> scheduler = new PriorityScheduler<>();
  private final Semaphore signal = new Semaphore(0);
  private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
  private final Map<String, Integer> generations = new ConcurrentHashMap<>();
  private final DedupIndex dedup = new DedupIndex();
  private final AckLedger ledger = new AckLedger();
  private final Set<String> receivedCounted = ConcurrentHashMap.newKeySet();
  private final Set<String> userAckCounted = ConcurrentHashMap.newKeySet();
  private final Set<String> failedCounted = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean running = new AtomicBoolean(true);
  private volatile Thread dispatcher;

  public FanoutEngine(AlertRepo alerts, DeliveryRepo deliveries, SessionRegistry sessions,
                      RetryPolicy retryPolicy, ScheduledExecutorService timers, Clock clock,
                      MetricsRegistry metrics, AuditService audit, StatusNotifier status) {
    this.alerts = Objects.requireNonNull(alerts);
    this.deliveries = Objects.requireNonNull(deliveries);
    this.sessions = Objects.requireNonNull(sessions);
    this.retryPolicy = Objects.requireNonNull(retryPolicy);
    this.timers = Objects.requireNonNull(timers);
    this.clock = Objects.requireNonNull(clock);
    this.metrics = Objects.requireNonNull(metrics);
    this.audit = Objects.requireNonNull(audit);
    this.status = Objects.requireNonNull(status);
  }

  /** Starts the single dispatcher thread (virtual-thread friendly blocking loop). */
  public void start(java.util.concurrent.Executor executor) {
    executor.execute(this::dispatchLoop);
  }

  // ---- enqueue paths ------------------------------------------------------

  /** New deliveries from a committed publish (dedup-gated first dispatch). */
  @Override
  public void enqueue(List<Delivery> batch) {
    for (Delivery d : batch) track(d, true);
  }

  /** Recovery/reconnect path: continues an owned lifecycle, bypasses dedup. */
  public void recoverEnqueue(List<Delivery> batch) {
    for (Delivery d : batch) track(d, false);
  }

  private void track(Delivery d, boolean fresh) {
    if (!running.get()) return;
    try {
      Optional<Alert> alertOpt = alerts.findById(d.alertId());
      if (alertOpt.isEmpty()) return;
      Alert alert = alertOpt.get();
      if (alert.state() != AlertState.ACTIVE) {
        // tombstoned while queued: nothing to deliver
        deliveries.updateState(d.id(), DeliveryState.CANCELLED, null, d.attemptCount());
        return;
      }
      Optional<AlertRevision> rev = alerts.findRevision(d.alertId(), d.revision());
      if (rev.isEmpty()) return;
      Tracked t = new Tracked(d.id(), alert, rev.get(), fresh);
      if (tracked.putIfAbsent(d.id(), t) != null) {
        return; // already owned (idempotent enqueue)
      }
      generations.putIfAbsent(d.id(), 0);
      openLedger(alert.id(), d.revision());
      enqueueWhenDue(t, d, true);
    } catch (SQLException e) {
      LOG.warn("enqueue failed delivery={}: {}", d.id(), e.toString());
    }
  }

  /**
   * Dispatch now unless durable state says the retry deadline is still ahead.
   * {@code allowReschedule} is true for recovery enqueue (which owns the timer)
   * and false for reconnect resume (the timer is already pending).
   */
  private void enqueueWhenDue(Tracked t, Delivery d, boolean allowReschedule) {
    if (d.attemptCount() > retryPolicy.maxAttempts()) {
      markFailed(t, d);
      return;
    }
    if (d.state() == DeliveryState.RETRY_SCHEDULED && d.nextRetryAt() != null) {
      long delay = Math.max(0, java.time.Duration.between(clock.instant(), d.nextRetryAt()).toMillis());
      if (delay > 0) {
        if (allowReschedule) {
          try {
            timers.schedule(() -> {
              scheduler.offer(t.alert().priority(), new Dispatch(d.id()));
              signal.release();
            }, delay, TimeUnit.MILLISECONDS);
          } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            untrack(d.id());
          }
        }
        return;
      }
    }
    scheduler.offer(t.alert().priority(), new Dispatch(d.id()));
    signal.release();
  }

  private void openLedger(String alertId, int revision) throws SQLException {
    List<String> expected = deliveries.findByAlert(alertId).stream()
        .filter(x -> x.revision() == revision)
        .map(Delivery::deviceId)
        .toList();
    try {
      ledger.open(alertId, revision, expected);
    } catch (IllegalStateException alreadyOpen) {
      // expected: ledger is per alert, opened once
    }
  }

  // ---- dispatcher ----------------------------------------------------------

  private void dispatchLoop() {
    dispatcher = Thread.currentThread();
    while (running.get()) {
      try {
        signal.acquire();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      Optional<Dispatch> next;
      while (running.get() && (next = scheduler.poll()).isPresent()) {
        try {
          dispatch(next.get().deliveryId());
        } catch (RuntimeException e) {
          LOG.warn("dispatch error delivery={}", next.get().deliveryId(), e);
        }
      }
    }
  }

  private void dispatch(String deliveryId) {
    Tracked t = tracked.get(deliveryId);
    if (t == null) return;
    Delivery d;
    try {
      Optional<Delivery> current = deliveries.findById(deliveryId);
      if (current.isEmpty()) { untrack(deliveryId); return; }
      d = current.get();
      if (settled(d.state())) { untrack(deliveryId); return; }
      if (d.attemptCount() > retryPolicy.maxAttempts()) {
        markFailed(t, d);
        return;
      }
    } catch (SQLException e) {
      LOG.warn("dispatch reload failed delivery={}: {}", deliveryId, e.toString());
      return;
    }
    try {
      Optional<Alert> currentAlert = alerts.findById(d.alertId());
      if (currentAlert.isEmpty()) { untrack(deliveryId); return; }
      if (currentAlert.get().state() != AlertState.ACTIVE) {
        deliveries.updateState(deliveryId, DeliveryState.CANCELLED, null, d.attemptCount());
        untrack(deliveryId);
        return;
      }
      if (d.revision() < currentAlert.get().currentRevision()) {
        // superseded revision (ALERT_UPDATE went out): tombstone the stale delivery
        deliveries.updateState(deliveryId, DeliveryState.CANCELLED, null, d.attemptCount());
        audit.append(null, "SUPERSEDED", d.alertId(), "CANCELLED", deliveryId,
            Json.obj().put("revision", d.revision())
                .put("currentRevision", currentAlert.get().currentRevision()));
        untrack(deliveryId);
        return;
      }
    } catch (SQLException e) {
      LOG.warn("supersede check failed delivery={}: {}", deliveryId, e.toString());
      return;
    }
    ClientSession session = sessions.deviceSession(d.deviceId());
    if (session == null || session.isClosed()) {
      offlineAttempt(t, d);
      return;
    }
    if (t.fresh() && d.attemptCount() == 0
        && dedup.seen(d.alertId(), d.revision(), d.deviceId())) {
      try {
        deliveries.updateState(deliveryId, DeliveryState.CANCELLED, null, d.attemptCount());
        audit.append(null, "DEDUP_SUPPRESS", d.alertId(), "SUPPRESSED", deliveryId,
            Json.obj().put("deviceId", d.deviceId()).put("revision", d.revision()));
      } catch (SQLException e) {
        LOG.warn("dedup suppress failed delivery={}: {}", deliveryId, e.toString());
      }
      untrack(deliveryId);
      return;
    }
    sendPush(t, d, session);
  }

  private static boolean settled(DeliveryState state) {
    // S-10: FAILED is terminal for the retry budget — recovered FAILED deliveries
    // are untracked without spending new attempts (they remain visible via SYNC).
    return state == DeliveryState.RECEIVED || state == DeliveryState.ACKNOWLEDGED
        || state == DeliveryState.FAILED || state == DeliveryState.EXPIRED
        || state == DeliveryState.CANCELLED;
  }

  private void sendPush(Tracked t, Delivery d, ClientSession session) {
    String messageId = Json.uuid();
    Envelope push = buildPush(t, d, messageId, session);
    int attempt = d.attemptCount() + 1;
    // S-02: commit the attempt + SENT BEFORE the frame can hit the socket (the
    // writer thread may flush it the instant it is offered). The device can ACK
    // immediately; the guarded transition guarantees a late dispatch commit can
    // never regress a concurrent RECEIVED/ACKNOWLEDGED back to SENT.
    try {
      deliveries.recordAttempt(d.id(), messageId, "SENT");
      deliveries.updateDispatchState(d.id(), DeliveryState.SENT, null, attempt);
    } catch (SQLException e) {
      LOG.warn("record attempt failed delivery={}: {}", d.id(), e.toString());
    }
    BoundedWriterQueue.OfferResult result = session.send(t.alert().priority(), push);
    if (result == BoundedWriterQueue.OfferResult.REJECTED_SLOW) {
      // policy already closed the slow session; the SENT attempt recorded above
      // IS this cycle's attempt — schedule the retry without counting a second
      // one (the frame never reached the wire).
      long delay = windowMs(attempt);
      try {
        deliveries.updateDispatchState(d.id(), DeliveryState.RETRY_SCHEDULED,
            clock.instant().plusMillis(delay), attempt);
      } catch (SQLException e) {
        LOG.warn("slow-client retry schedule failed delivery={}: {}", d.id(), e.toString());
      }
      scheduleCheck(t, attempt);
      return;
    }
    status.pushWritten(d.deviceId());
    if (attempt > 1) {
      metrics.incRetries();
      audit.append(null, "RETRY", d.alertId(), "SENT", d.id(),
          Json.obj().put("attempt", attempt).put("messageId", messageId));
    }
    scheduleCheck(t, attempt);
  }

  private void offlineAttempt(Tracked t, Delivery d) {
    int attempt = d.attemptCount() + 1;
    long delay = windowMs(attempt);
    Instant nextRetry = clock.instant().plusMillis(delay);
    try {
      deliveries.recordAttempt(d.id(), Json.uuid(), "OFFLINE");
      deliveries.scheduleRetry(d.id(), nextRetry);
    } catch (SQLException e) {
      LOG.warn("offline schedule failed delivery={}: {}", d.id(), e.toString());
    }
    scheduleCheck(t, attempt);
  }

  /** Ack-wait window after attempt n: 3s/6s/12s (+jitter), capped at the last tier. */
  private long windowMs(int attempt) {
    int index = Math.min(Math.max(attempt - 1, 0), Math.max(retryPolicy.maxAttempts() - 1, 0));
    return retryPolicy.delayMs(index);
  }

  private void scheduleCheck(Tracked t, int attempt) {
    int generation = generations.merge(t.deliveryId(), 1, Integer::sum);
    long delay = windowMs(attempt);
    try {
      timers.schedule(() -> checkDelivery(t.deliveryId(), generation), delay, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
      // timers already stopped (server shutdown / test teardown): nothing to schedule
    }
  }

  private void checkDelivery(String deliveryId, int generation) {
    if (!running.get()) return;
    Integer current = generations.get(deliveryId);
    if (current == null || current != generation) return; // superseded by a newer attempt
    Tracked t = tracked.get(deliveryId);
    if (t == null) return;
    try {
      Optional<Delivery> opt = deliveries.findById(deliveryId);
      if (opt.isEmpty()) { untrack(deliveryId); return; }
      Delivery d = opt.get();
      if (settled(d.state())) { untrack(deliveryId); return; }
      int retriesConsumed = d.attemptCount() - 1;
      if (retryPolicy.shouldRetry(retriesConsumed)) {
        scheduler.offer(t.alert().priority(), new Dispatch(deliveryId));
        signal.release();
      } else {
        markFailed(t, d);
      }
    } catch (SQLException e) {
      LOG.warn("retry check failed delivery={}: {}", deliveryId, e.toString());
    }
  }

  private void markFailed(Tracked t, Delivery d) {
    boolean failed;
    try {
      failed = deliveries.markFailed(d.id());
    } catch (SQLException e) {
      LOG.warn("mark FAILED failed delivery={}: {}", d.id(), e.toString());
      return;
    }
    if (!failed) {
      // A concurrent ACK (RECEIVED/ACKNOWLEDGED) or an already-terminal state won
      // the race between checkDelivery's read and this write: the delivery did NOT
      // fail. Never regress it to FAILED nor count/audit a failure (H2).
      untrack(d.id());
      return;
    }
    if (failedCounted.add(d.id())) {
      metrics.incFailed();
      status.deliveryFailed(d.deviceId());
    }
    audit.append(null, "DELIVERY_FAILED", d.alertId(), "FAILED", d.id(),
        Json.obj().put("deviceId", d.deviceId()).put("attempts", d.attemptCount()));
    untrack(d.id());
  }

  private void untrack(String deliveryId) {
    tracked.remove(deliveryId);
    generations.remove(deliveryId);
  }

  // ---- reconnect resume -----------------------------------------------------

  /** Device (re)connected: immediately retry everything it still owes. */
  public void resumeDevice(String deviceId) {
    for (Tracked t : tracked.values()) {
      Delivery d;
      try {
        Optional<Delivery> opt = deliveries.findById(t.deliveryId());
        if (opt.isEmpty()) continue;
        d = opt.get();
      } catch (SQLException e) { continue; }
      if (d.deviceId().equals(deviceId) && !settled(d.state())) {
        enqueueWhenDue(t, d, false);
      }
    }
  }

  // ---- ACK path ---------------------------------------------------------------

  /** Called after DeliveryRepo.recordAck committed; advances ledger + metrics. */
  public void onAck(String alertId, String deliveryId, String deviceId,
                    aegis.persistence.AckKind kind, Instant ackedAt) {
    Tracked trackedAck = tracked.get(deliveryId);
    onAck(alertId, deliveryId, trackedAck == null ? 1 : trackedAck.revision().revision(),
        deviceId, kind, ackedAt);
  }

  /** Router-validated revision: a late ACK must complete only that revision. */
  public void onAck(String alertId, String deliveryId, int revision, String deviceId,
                    aegis.persistence.AckKind kind, Instant ackedAt) {
    boolean advanced = ledger.ack(alertId, revision, deviceId);
    if (kind == aegis.persistence.AckKind.RECEIVED) {
      if (receivedCounted.add(deliveryId)) {
        metrics.incAckReceived();
        status.ackReceived(deviceId);
        try {
          alerts.findById(alertId).ifPresent(a ->
              metrics.recordLatency(Math.max(0, ackedAt.toEpochMilli() - a.createdAt().toEpochMilli())));
        } catch (SQLException e) {
          LOG.warn("latency sample failed alert={}: {}", alertId, e.toString());
        }
      }
    } else if (kind == aegis.persistence.AckKind.USER) {
      if (userAckCounted.add(deliveryId)) {
        metrics.incAckUser();
        status.ackUser(deviceId);
      }
    }
    if (kind != aegis.persistence.AckKind.CANCEL) {
      untrack(deliveryId); // RECEIVED/USER stop the retry cycle
    }
    if (advanced && ledger.complete(alertId, revision)) {
      audit.append(null, "ALERT_FULLY_ACKED", alertId, "OK", null);
    }
  }

  /** Cancel tombstone: stop retries for the alert (DB states set by AlertService). */
  public void cancelAlert(String alertId) {
    for (Tracked t : tracked.values()) {
      if (t.alert().id().equals(alertId)) {
        untrack(t.deliveryId());
      }
    }
  }

  /** Direct control fanout (CANCEL_PUSH etc.) to online devices, priority-queued. */
  public void fanoutControl(Priority priority, Envelope envelope, List<String> deviceIds) {
    for (String deviceId : deviceIds) {
      ClientSession session = sessions.deviceSession(deviceId);
      if (session != null) {
        session.send(priority, envelope);
      }
    }
  }

  public Envelope buildPush(Tracked t, Delivery d, String messageId, ClientSession session) {
    return buildPush(t.alert(), t.revision(), d, messageId, session);
  }

  /** Builds the ALERT_PUSH envelope (fixture shape, docs/PROTOCOL.md). */
  public Envelope buildPush(Alert alert, AlertRevision revision, Delivery d,
                            String messageId, ClientSession session) {
    ObjectNode payload = Json.obj();
    JsonNode meta = parseMeta(revision.metadataJson());
    payload.put("alertId", alert.id());
    payload.put("revision", revision.revision());
    payload.put("deliveryId", d.id());
    payload.put("buildingId", alert.buildingId());
    payload.put("category", alert.category());
    payload.put("priority", alert.priority().name());
    payload.put("title", revision.title());
    payload.put("instruction", revision.body());
    if (meta.hasNonNull("incidentId")) payload.put("incidentId", meta.get("incidentId").asText());
    payload.put("simulation", meta.path("simulation").asBoolean(true));
    payload.put("urgency", meta.path("urgency").asText("Immediate"));
    payload.put("severity", meta.path("severity").asText("Severe"));
    payload.put("certainty", meta.path("certainty").asText("Observed"));
    if (alert.expiresAt() != null) payload.put("expiresAt", Json.ts(alert.expiresAt()));
    if (session != null) {
      if (session.floorNumber() > 0) payload.put("floorNumber", session.floorNumber());
      if (session.zoneCode() != null) payload.put("zoneCode", session.zoneCode());
    }
    return new Envelope(Protocol.VERSION, "ALERT_PUSH", messageId, d.id(),
        Json.ts(clock.instant()), payload);
  }

  private static JsonNode parseMeta(String metadataJson) {
    try {
      JsonNode n = Json.MAPPER.readTree(metadataJson == null ? "{}" : metadataJson);
      return n.isObject() ? n : Json.obj();
    } catch (Exception e) {
      return Json.obj();
    }
  }

  public AckLedger ledger() { return ledger; }
  public int trackedCount() { return tracked.size(); }

  public void shutdown() {
    running.set(false);
    signal.release();
    Thread d = dispatcher;
    if (d != null) d.interrupt();
  }
}
