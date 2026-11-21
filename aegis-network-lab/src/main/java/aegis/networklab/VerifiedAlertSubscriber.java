package aegis.networklab;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The secure subscriber pattern: joinGroup → receive advisory ALERT_HINT → fetch authoritative
 * content over the verified TLS session → deliver ONLY TLS-verified content to the sink.
 * Hints are triggers, never content: a forged hint (wrong revision/alertId) at worst causes a
 * fetch; the sink always sees the server's authoritative revision. Stale/duplicate revisions
 * (≤ last VERIFIED revision) are ignored without a fetch.
 */
public final class VerifiedAlertSubscriber implements HintListener {

  public record VerifiedDelivery(AlertContent alert, AlertHint triggeringHint) {}

  private final LabTlsClient client;
  private final Consumer<VerifiedDelivery> sink;
  private final java.util.function.BiConsumer<AlertHint, Exception> failureHook;
  private final Map<String, Long> lastVerifiedRevision = new HashMap<>();
  private final Map<String, Long> lastFetchedHintRevision = new HashMap<>();
  private final AtomicLong hintsReceived = new AtomicLong();
  private final AtomicLong fetchesIssued = new AtomicLong();
  private final AtomicLong verifiedDelivered = new AtomicLong();
  private final AtomicLong staleIgnored = new AtomicLong();
  private final AtomicLong fetchMisses = new AtomicLong();
  private final AtomicLong fetchFailures = new AtomicLong();

  public VerifiedAlertSubscriber(LabTlsClient client, Consumer<VerifiedDelivery> sink) {
    this(client, sink, (h, ex) -> { });
  }

  public VerifiedAlertSubscriber(LabTlsClient client, Consumer<VerifiedDelivery> sink,
                                 java.util.function.BiConsumer<AlertHint, Exception> failureHook) {
    this.client = client;
    this.sink = sink;
    this.failureHook = failureHook;
  }

  @Override
  public void onHint(AlertHint hint) {
    hintsReceived.incrementAndGet();
    synchronized (this) { // one TLS socket; serialize fetch + revision-guard updates
      Long lastVerified = lastVerifiedRevision.get(hint.alertId());
      Long lastHintFetched = lastFetchedHintRevision.get(hint.alertId());
      // Stale: hint revision ≤ last VERIFIED revision. Duplicate: exact same hint revision
      // already fetched (coalesces repeated/forged hints; TLS truth was already consulted).
      // A forged high revision can suppress a later genuine hint of the SAME number only —
      // production clients additionally poll SYNC_REQUEST periodically (advisory channel).
      if ((lastVerified != null && hint.revision() <= lastVerified)
          || (lastHintFetched != null && hint.revision() == lastHintFetched.longValue())) {
        staleIgnored.incrementAndGet();
        return;
      }
      fetchesIssued.incrementAndGet();
      lastFetchedHintRevision.put(hint.alertId(), hint.revision());
      Optional<AlertContent> fetched;
      try {
        fetched = client.fetchAlert(hint.alertId());
      } catch (Exception ex) {
        fetchFailures.incrementAndGet();
        lastFetchedHintRevision.remove(hint.alertId()); // allow retry after failure
        failureHook.accept(hint, ex);
        return;
      }
      if (fetched.isEmpty()) {
        fetchMisses.incrementAndGet();
        lastFetchedHintRevision.remove(hint.alertId()); // alert may appear later
        return;
      }
      AlertContent content = fetched.get();
      Long now = lastVerifiedRevision.get(hint.alertId());
      if (now != null && content.revision() <= now) {
        staleIgnored.incrementAndGet(); // authoritative content not newer than what we displayed
        return;
      }
      lastVerifiedRevision.put(hint.alertId(), content.revision());
      verifiedDelivered.incrementAndGet();
      sink.accept(new VerifiedDelivery(content, hint));
    }
  }

  public long hintsReceived() { return hintsReceived.get(); }
  public long fetchesIssued() { return fetchesIssued.get(); }
  public long verifiedDelivered() { return verifiedDelivered.get(); }
  public long staleIgnored() { return staleIgnored.get(); }
  public long fetchMisses() { return fetchMisses.get(); }
  public long fetchFailures() { return fetchFailures.get(); }
}
