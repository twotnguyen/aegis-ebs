package aegis.networklab;

/** Authoritative alert content as served over TLS (the ONLY displayable source). */
public record AlertContent(String alertId, long revision, String title, String body,
                           String priority, String issuedAt) {}
