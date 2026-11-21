package aegis.networklab;

/** Trust violation: UDP nonce mismatch, TLS identity mismatch, or HELLO nonce not echoed. */
public class DiscoveryRejectedException extends Exception {
  public DiscoveryRejectedException(String m) { super(m); }
  public DiscoveryRejectedException(String m, Throwable c) { super(m, c); }
}
