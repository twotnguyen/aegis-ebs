package aegis.networklab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires one hint publisher + N subscribers, choosing the transport by a REAL probe:
 * multicast when the group actually delivers on the configured interface, otherwise
 * per-subscriber UDP unicast (macOS loopback case). The advisory semantics are identical
 * in both modes — hints only trigger TLS fetches.
 */
public final class FallbackHintBus implements AutoCloseable {

  public enum Mode { MULTICAST, UNICAST_FALLBACK }

  private final Mode mode;
  private final MulticastSupport.ProbeResult probe;
  private final HintPublisher publisher;
  private final List<HintSubscriber> subscribers;

  public FallbackHintBus(MulticastConfig cfg, int subscriberCount, HintListener listener) throws IOException {
    this(cfg, subscriberCount, i -> listener);
  }

  /** Factory variant: each logical subscriber can own its listener (e.g. its own TLS client). */
  public FallbackHintBus(MulticastConfig cfg, int subscriberCount,
                         java.util.function.IntFunction<HintListener> listenerFactory) throws IOException {
    if (subscriberCount < 1) throw new IllegalArgumentException("need >= 1 subscriber");
    this.probe = MulticastSupport.probe(cfg);
    this.subscribers = new ArrayList<>(subscriberCount);
    HintPublisher pub = null;
    boolean ok = false;
    try {
      if (probe.available()) {
        pub = new MulticastHintPublisher(cfg);
        for (int i = 0; i < subscriberCount; i++)
          subscribers.add(new MulticastHintSubscriber(cfg, listenerFactory.apply(i)));
        this.mode = Mode.MULTICAST;
      } else {
        UnicastHintPublisher up = new UnicastHintPublisher();
        pub = up;
        for (int i = 0; i < subscriberCount; i++) {
          UnicastHintSubscriber s = new UnicastHintSubscriber(listenerFactory.apply(i));
          subscribers.add(s);
          up.addTarget(s.unicastEndpoint());
        }
        this.mode = Mode.UNICAST_FALLBACK;
      }
      ok = true;
    } finally {
      if (!ok) {
        for (HintSubscriber s : subscribers) s.close();
        if (pub != null) pub.close();
      }
    }
    this.publisher = pub;
  }

  public Mode mode() { return mode; }
  public MulticastSupport.ProbeResult probe() { return probe; }
  public HintPublisher publisher() { return publisher; }
  public List<HintSubscriber> subscribers() { return List.copyOf(subscribers); }

  public void publish(AlertHint hint) throws IOException, LabCodecException { publisher.publish(hint); }

  @Override
  public void close() {
    for (HintSubscriber s : subscribers) s.close();
    publisher.close();
  }
}
