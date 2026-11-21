package aegis.networklab;

import java.net.InetSocketAddress;

/** Subscriber side of the advisory hint channel. */
public interface HintSubscriber extends AutoCloseable {
  long hintsReceived();
  long bytesReceived();
  long invalidDropped();
  /** Unicast fallback: local endpoint to register with the publisher. Multicast: null. */
  InetSocketAddress unicastEndpoint();
  @Override void close();
}
