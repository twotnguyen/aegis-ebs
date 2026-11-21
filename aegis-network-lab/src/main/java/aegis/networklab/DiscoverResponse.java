package aegis.networklab;

/**
 * DISCOVER_EBS reply. UNTRUSTED until TLS verification (see DiscoveryClient.discoverTrusted):
 * anyone on the path can spoof a UDP reply, so these fields are only a pointer to where to
 * attempt an authenticated TLS handshake — never a source of displayable content.
 */
public record DiscoverResponse(String serverHost, int tlsPort, String nonce, String serverId) {
  public static final String TYPE = "DISCOVER_EBS_REPLY";
}
