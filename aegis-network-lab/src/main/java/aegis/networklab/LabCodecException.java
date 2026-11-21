package aegis.networklab;

/** Malformed lab datagram (discovery request/reply or ALERT_HINT). */
public class LabCodecException extends Exception {
  public LabCodecException(String m) { super(m); }
  public LabCodecException(String m, Throwable c) { super(m, c); }
}
