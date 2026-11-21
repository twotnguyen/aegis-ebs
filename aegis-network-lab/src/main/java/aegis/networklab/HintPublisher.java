package aegis.networklab;

import java.io.IOException;

/** Publisher side of the advisory hint channel (multicast or unicast fallback). */
public interface HintPublisher extends AutoCloseable {
  void publish(AlertHint hint) throws IOException, LabCodecException;
  /** Datagram send() calls = copies placed on the source link. */
  long packetsSent();
  long bytesSent();
  @Override void close();
}
