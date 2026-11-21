package aegis.simulator;

import java.util.concurrent.atomic.LongAdder;

/** Fleet-wide counters (thread-safe, virtual-thread friendly). */
public final class SimStats {

  public final LongAdder agentsStarted = new LongAdder();
  public final LongAdder agentsFinished = new LongAdder();
  public final LongAdder connectSuccess = new LongAdder();
  public final LongAdder connectFailures = new LongAdder();
  public final LongAdder reconnects = new LongAdder();
  public final LongAdder alertsReceived = new LongAdder();
  public final LongAdder duplicatePushes = new LongAdder();
  public final LongAdder cancelsReceived = new LongAdder();
  public final LongAdder acksSent = new LongAdder();
  public final LongAdder acksDropped = new LongAdder();
  public final LongAdder acksDelayed = new LongAdder();
  public final LongAdder duplicateAcks = new LongAdder();
  public final LongAdder disconnects = new LongAdder();
  public final LongAdder syncRequests = new LongAdder();
  public final LongAdder heartbeats = new LongAdder();
  public final LongAdder connectionErrors = new LongAdder();
  /** Permanent AUTH_RESULT ok=false rejections — agent stops, no retry (fail-fast). */
  public final LongAdder authRejections = new LongAdder();

  public record Snapshot(
      long agentsStarted, long agentsFinished, long connectSuccess, long connectFailures,
      long reconnects, long alertsReceived, long duplicatePushes, long cancelsReceived,
      long acksSent, long acksDropped, long acksDelayed, long duplicateAcks,
      long disconnects, long syncRequests, long heartbeats, long connectionErrors,
      long authRejections) {

    public String summary() {
      return """
          ==== AEGIS SIMULATOR SUMMARY (SIMULATION/TRAINING) ====
          agents started/finished : %d / %d
          connects ok/failed      : %d / %d   reconnects: %d
          alerts received         : %d   (duplicate pushes: %d, cancels: %d)
          ACK_RECEIVED sent       : %d   dropped: %d   delayed: %d   extra copies: %d
          fault disconnects       : %d   SYNC requests: %d   heartbeats answered: %d
          connection errors       : %d   auth rejections (permanent): %d
          =======================================================""".formatted(
          agentsStarted, agentsFinished, connectSuccess, connectFailures, reconnects,
          alertsReceived, duplicatePushes, cancelsReceived,
          acksSent, acksDropped, acksDelayed, duplicateAcks,
          disconnects, syncRequests, heartbeats, connectionErrors, authRejections);
    }
  }

  public Snapshot snapshot() {
    return new Snapshot(
        agentsStarted.sum(), agentsFinished.sum(), connectSuccess.sum(), connectFailures.sum(),
        reconnects.sum(), alertsReceived.sum(), duplicatePushes.sum(), cancelsReceived.sum(),
        acksSent.sum(), acksDropped.sum(), acksDelayed.sum(), duplicateAcks.sum(),
        disconnects.sum(), syncRequests.sum(), heartbeats.sum(), connectionErrors.sum(),
        authRejections.sum());
  }
}
