package aegis.networklab;

/**
 * Advisory multicast hint (Bài 8): "alert {@code alertId} may have a new revision".
 * NOT a trusted command — carries no content, and the subscriber must fetch the authoritative
 * alert over authenticated TLS before display. {@code revision} is monotonic per alert.
 */
public record AlertHint(String alertId, long revision, String serverId) {
  public static final String TYPE = "ALERT_HINT";
}
