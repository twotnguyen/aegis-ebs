package aegis.networklab;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** TDD unit tests: lab datagram codec (ALERT_HINT + DISCOVER_EBS nonce echo fields). */
class LabDatagramCodecTest {

  private final LabDatagramCodec codec = new LabDatagramCodec();
  private static final String NONCE = "0123456789abcdef0123456789abcdef";

  // ---- round trips ----
  @Test
  void discoverRequestRoundTrip() throws Exception {
    byte[] d = codec.encode(new DiscoverRequest(NONCE, 2));
    DiscoverRequest r = codec.decodeRequest(d);
    assertEquals(NONCE, r.nonce());
    assertEquals(2, r.version());
    assertTrue(d.length <= LabDatagramCodec.MAX_DATAGRAM_BYTES);
    assertEquals(DiscoverRequest.TYPE, codec.peekType(d));
  }

  @Test
  void discoverResponseRoundTripPreservesNonceEcho() throws Exception {
    byte[] d = codec.encode(new DiscoverResponse("127.0.0.1", 9443, NONCE, "aegis-lab-server"));
    DiscoverResponse r = codec.decodeResponse(d);
    assertEquals("127.0.0.1", r.serverHost());
    assertEquals(9443, r.tlsPort());
    assertEquals(NONCE, r.nonce(), "nonce must survive encode/decode verbatim for the echo check");
    assertEquals("aegis-lab-server", r.serverId());
  }

  @Test
  void alertHintRoundTrip() throws Exception {
    byte[] d = codec.encode(new AlertHint("alert-42", 7, "aegis-lab-server"));
    AlertHint h = codec.decodeHint(d);
    assertEquals("alert-42", h.alertId());
    assertEquals(7, h.revision());
    assertEquals("aegis-lab-server", h.serverId());
    assertEquals(AlertHint.TYPE, codec.peekType(d));
    // hint is small: fits one datagram well below Ethernet MTU (Bài 7.1.1)
    assertTrue(d.length < 200, "advisory hint should be tiny, was " + d.length);
  }

  @Test
  void hintRevisionIsMonotonicLong() throws Exception {
    long big = 4_000_000_000L;
    AlertHint h = codec.decodeHint(codec.encode(new AlertHint("a", big, "s")));
    assertEquals(big, h.revision());
  }

  // ---- rejects: structure ----
  @Test
  void rejectsNullEmptyOversized() {
    assertThrows(LabCodecException.class, () -> codec.decodeHint(null));
    assertThrows(LabCodecException.class, () -> codec.decodeHint(new byte[0]));
    assertThrows(LabCodecException.class, () -> codec.decodeHint(new byte[LabDatagramCodec.MAX_DATAGRAM_BYTES + 1]));
    assertThrows(LabCodecException.class, () -> codec.decodeRequest(new byte[2048]));
  }

  @Test
  void rejectsInvalidUtf8() {
    byte[] bad = {(byte) 0xFF, (byte) 0xFE, 0x00};
    assertThrows(LabCodecException.class, () -> codec.decodeHint(bad));
  }

  @Test
  void rejectsNonObjectAndTrailingTokens() throws Exception {
    assertThrows(LabCodecException.class, () -> codec.decodeHint("[]".getBytes(StandardCharsets.UTF_8)));
    assertThrows(LabCodecException.class, () -> codec.decodeHint("42".getBytes(StandardCharsets.UTF_8)));
    byte[] hint = codec.encode(new AlertHint("a", 1, "s"));
    byte[] extra = "{}".getBytes(StandardCharsets.UTF_8);
    byte[] withTrail = new byte[hint.length + 1 + extra.length];
    System.arraycopy(hint, 0, withTrail, 0, hint.length);
    withTrail[hint.length] = ' ';
    System.arraycopy(extra, 0, withTrail, hint.length + 1, extra.length);
    assertThrows(LabCodecException.class, () -> codec.decodeHint(withTrail));
  }

  @Test
  void rejectsWrongOrMissingType() throws Exception {
    byte[] request = codec.encode(new DiscoverRequest(NONCE, 2));
    assertThrows(LabCodecException.class, () -> codec.decodeHint(request));
    assertThrows(LabCodecException.class,
        () -> codec.decodeHint("{\"alertId\":\"a\",\"revision\":1,\"serverId\":\"s\"}".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsUnknownFields() throws Exception {
    byte[] hint = codec.encode(new AlertHint("a", 1, "s"));
    String json = new String(hint, StandardCharsets.UTF_8);
    String injected = json.substring(0, json.length() - 1) + ",\"evil\":\"x\"}";
    assertThrows(LabCodecException.class, () -> codec.decodeHint(injected.getBytes(StandardCharsets.UTF_8)));
  }

  // ---- rejects: discovery fields ----
  @Test
  void rejectsBadVersion() throws Exception {
    assertThrows(LabCodecException.class, () -> codec.encode(new DiscoverRequest(NONCE, 1)));
    assertThrows(LabCodecException.class, () -> codec.encode(new DiscoverRequest(NONCE, 3)));
    String v1 = "{\"type\":\"DISCOVER_EBS\",\"version\":1,\"nonce\":\"" + NONCE + "\"}";
    assertThrows(LabCodecException.class, () -> codec.decodeRequest(v1.getBytes(StandardCharsets.UTF_8)));
    String vStr = "{\"type\":\"DISCOVER_EBS\",\"version\":\"2\",\"nonce\":\"" + NONCE + "\"}";
    assertThrows(LabCodecException.class, () -> codec.decodeRequest(vStr.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsBadNonce() throws Exception {
    assertThrows(LabCodecException.class, () -> codec.encode(new DiscoverRequest(null, 2)));
    assertThrows(LabCodecException.class, () -> codec.encode(new DiscoverRequest("short", 2)));
    assertThrows(LabCodecException.class, () -> codec.encode(new DiscoverRequest("has space 12345678", 2)));
    String missing = "{\"type\":\"DISCOVER_EBS\",\"version\":2}";
    assertThrows(LabCodecException.class, () -> codec.decodeRequest(missing.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsBadResponseFields() throws Exception {
    assertThrows(LabCodecException.class,
        () -> codec.encode(new DiscoverResponse("127.0.0.1", 0, NONCE, "s")));
    assertThrows(LabCodecException.class,
        () -> codec.encode(new DiscoverResponse("127.0.0.1", 70000, NONCE, "s")));
    assertThrows(LabCodecException.class,
        () -> codec.encode(new DiscoverResponse("", 9443, NONCE, "s")));
    String portStr = "{\"type\":\"DISCOVER_EBS_REPLY\",\"serverHost\":\"h\",\"tlsPort\":\"9443\",\"nonce\":\""
        + NONCE + "\",\"serverId\":\"s\"}";
    assertThrows(LabCodecException.class, () -> codec.decodeResponse(portStr.getBytes(StandardCharsets.UTF_8)));
  }

  // ---- rejects: hint fields ----
  @Test
  void rejectsBadHintFields() throws Exception {
    assertThrows(LabCodecException.class, () -> codec.encode(new AlertHint("a", 0, "s")));
    assertThrows(LabCodecException.class, () -> codec.encode(new AlertHint("a", -3, "s")));
    assertThrows(LabCodecException.class, () -> codec.encode(new AlertHint("", 1, "s")));
    assertThrows(LabCodecException.class, () -> codec.encode(new AlertHint("a", 1, null)));
    String revStr = "{\"type\":\"ALERT_HINT\",\"alertId\":\"a\",\"revision\":\"7\",\"serverId\":\"s\"}";
    assertThrows(LabCodecException.class, () -> codec.decodeHint(revStr.getBytes(StandardCharsets.UTF_8)));
    String revZero = "{\"type\":\"ALERT_HINT\",\"alertId\":\"a\",\"revision\":0,\"serverId\":\"s\"}";
    assertThrows(LabCodecException.class, () -> codec.decodeHint(revZero.getBytes(StandardCharsets.UTF_8)));
  }
}
