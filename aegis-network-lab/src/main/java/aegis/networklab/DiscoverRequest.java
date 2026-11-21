package aegis.networklab;

/**
 * DISCOVER_EBS request (Bài 7 UDP). {@code nonce} binds the reply to this exchange;
 * {@code version} is the EBSP protocol version the client speaks.
 */
public record DiscoverRequest(String nonce, int version) {
  public static final String TYPE = "DISCOVER_EBS";
}
