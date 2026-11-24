package aegis.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Content validation: UTF-8, JSON shape, version==2, MessageType, fields, lengths. */
class FrameCodecValidationTest {

  private final FrameCodec codec = new FrameCodecImpl();
  private static final ObjectMapper M = new ObjectMapper();

  private static Envelope sample() {
    return new Envelope(Protocol.VERSION, "HEARTBEAT", "m-1", "c-1",
        "2026-11-21T00:00:00Z", M.createObjectNode().put("seq", 1));
  }

  private static byte[] frame(byte[] body) {
    byte[] f = new byte[Protocol.PREFIX_BYTES + body.length];
    byte[] len = ByteBuffer.allocate(4).putInt(body.length).array();
    System.arraycopy(len, 0, f, 0, 4);
    System.arraycopy(body, 0, f, 4, body.length);
    return f;
  }

  private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

  private ObjectNode sampleJson() throws Exception {
    return (ObjectNode) M.readTree(utf8(new String(codec.encode(sample()), 4,
        codec.encode(sample()).length - 4, StandardCharsets.UTF_8)));
  }

  private byte[] mutated(java.util.function.Consumer<ObjectNode> mut) throws Exception {
    ObjectNode n = sampleJson();
    mut.accept(n);
    return frame(M.writeValueAsBytes(n));
  }

  @Test void encodeDecodeRoundTrip() throws Exception {
    Envelope e = sample();
    Envelope got = codec.decode(codec.encode(e));
    assertEquals(e, got);
    assertEquals(e.payload(), got.payload());
  }

  @Test void decodeRejectsInvalidUtf8() {
    byte[] body = {(byte) 0xFF, (byte) 0xFE, (byte) 0xC0, (byte) 0xAF};
    assertThrows(FrameException.class, () -> codec.decode(frame(body)));
    assertThrows(FrameException.class,
        () -> codec.readFrame(new ByteArrayInputStream(frame(body))));
  }

  @Test void decodeRejectsTruncatedUtf8Sequence() {
    byte[] body = utf8("{\"version\":2,\"type\":\"HEARTBEAT\",\"messageId\":\"m\",\"sentAt\":\"t\",\"payload\":{\"x\":\"");
    byte[] withDanglingLead = Arrays.copyOf(body, body.length + 1);
    withDanglingLead[body.length] = (byte) 0xC3; // lead byte of 2-byte seq, never completed
    assertThrows(FrameException.class, () -> codec.decode(frame(withDanglingLead)));
  }

  @Test void decodeRejectsInvalidJson() {
    assertThrows(FrameException.class, () -> codec.decode(frame(utf8("{\"version\":2,"))));
    assertThrows(FrameException.class, () -> codec.decode(frame(utf8("not json at all"))));
  }

  @Test void decodeRejectsNonObjectJson() {
    for (String s : new String[]{"[1,2,3]", "42", "\"text\"", "true", "null"}) {
      assertThrows(FrameException.class, () -> codec.decode(frame(utf8(s))), s);
    }
  }

  @Test void decodeRejectsWrongVersion() throws Exception {
    for (int v : new int[]{1, 3, 0, -2}) {
      byte[] wire = mutated(n -> n.put("version", v));
      assertThrows(FrameException.class, () -> codec.decode(wire), "version=" + v);
    }
  }

  @Test void decodeRejectsMissingVersion() throws Exception {
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.remove("version"))));
  }

  @Test void decodeRejectsUnknownType() throws Exception {
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.put("type", "NOT_A_TYPE"))));
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.put("type", "hello"))));
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.remove("type"))));
  }

  @Test void decodeRejectsMissingOrBlankMessageId() throws Exception {
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.remove("messageId"))));
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.put("messageId", ""))));
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.put("messageId", "  "))));
  }

  @Test void decodeRejectsMissingOrBlankSentAt() throws Exception {
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.remove("sentAt"))));
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.put("sentAt", ""))));
  }

  @Test void decodeRejectsMissingOrNullPayload() throws Exception {
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.remove("payload"))));
    assertThrows(FrameException.class, () -> codec.decode(mutated(n -> n.putNull("payload"))));
  }

  @Test void nullCorrelationIdIsAllowed() throws Exception {
    byte[] wire = mutated(n -> n.putNull("correlationId"));
    Envelope got = codec.decode(wire);
    assertNull(got.correlationId());
    Envelope noCorr = new Envelope(Protocol.VERSION, "HEARTBEAT", "m-nc", null,
        "2026-11-21T00:00:00Z", M.createObjectNode());
    assertNull(codec.decode(codec.encode(noCorr)).correlationId());
  }

  @Test void decodeRejectsTooShortFrame() {
    assertThrows(FrameException.class, () -> codec.decode(new byte[0]));
    assertThrows(FrameException.class, () -> codec.decode(new byte[4]));
    assertThrows(FrameException.class, () -> codec.decode(null));
  }

  @Test void decodeRejectsZeroLengthPrefix() {
    assertThrows(FrameException.class, () -> codec.decode(new byte[]{0, 0, 0, 0}));
  }

  @Test void decodeRejectsLengthPrefixMismatch() throws Exception {
    byte[] wire = codec.encode(sample());
    byte[] plusOne = wire.clone();
    plusOne[3] = (byte) (plusOne[3] + 1);
    assertThrows(FrameException.class, () -> codec.decode(plusOne));
    byte[] minusOne = wire.clone();
    minusOne[3] = (byte) (minusOne[3] - 1);
    assertThrows(FrameException.class, () -> codec.decode(minusOne));
  }

  @Test void decodeRejectsHugeDeclaredLengthBeforeAllocation() {
    byte[] tiny = {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, '{', '}'};
    assertThrows(FrameException.class, () -> codec.decode(tiny));
  }

  /** Builds a valid frame whose JSON body is exactly {@code target} bytes. */
  private byte[] frameWithExactBodySize(int target) throws Exception {
    int padLen = Math.max(1, target - 200);
    for (int i = 0; i < 10; i++) {
      ObjectNode p = M.createObjectNode().put("pad", "x".repeat(padLen));
      Envelope e = new Envelope(Protocol.VERSION, "HEARTBEAT", "m-big", "c-big",
          "2026-11-21T00:00:00Z", p);
      byte[] f = codec.encode(e);
      int body = f.length - Protocol.PREFIX_BYTES;
      if (body == target) return f;
      padLen = Math.max(1, padLen + (target - body));
    }
    throw new AssertionError("could not build body of size " + target);
  }

  @Test void exactlyMaxFrameBytesAccepted() throws Exception {
    byte[] f = frameWithExactBodySize(Protocol.MAX_FRAME_BYTES);
    assertEquals(Protocol.PREFIX_BYTES + Protocol.MAX_FRAME_BYTES, f.length);
    Envelope viaDecode = codec.decode(f);
    Envelope viaStream = codec.readFrame(new ByteArrayInputStream(f));
    assertEquals(viaDecode, viaStream);
    assertEquals("HEARTBEAT", viaDecode.type());
  }

  @Test void oneByteOverMaxRejected() throws Exception {
    byte[] max = frameWithExactBodySize(Protocol.MAX_FRAME_BYTES);
    byte[] body = Arrays.copyOfRange(max, 4, max.length);
    byte[] bigger = Arrays.copyOf(body, body.length + 1);
    bigger[body.length] = ' ';
    byte[] wire = frame(bigger);
    assertThrows(FrameException.class, () -> codec.decode(wire));
    assertThrows(FrameException.class, () -> codec.readFrame(new ByteArrayInputStream(wire)));
  }

  @Test void encodeRejectsWrongVersion() {
    Envelope e = new Envelope(1, "HEARTBEAT", "m", "c", "t", M.createObjectNode());
    assertThrows(FrameException.class, () -> codec.encode(e));
  }

  @Test void encodeRejectsUnknownType() {
    Envelope e = new Envelope(Protocol.VERSION, "BOGUS", "m", "c", "t", M.createObjectNode());
    assertThrows(FrameException.class, () -> codec.encode(e));
    Envelope nullType = new Envelope(Protocol.VERSION, null, "m", "c", "t", M.createObjectNode());
    assertThrows(FrameException.class, () -> codec.encode(nullType));
  }

  @Test void encodeRejectsMissingRequiredFields() {
    assertThrows(FrameException.class, () -> codec.encode(
        new Envelope(Protocol.VERSION, "HEARTBEAT", null, "c", "t", M.createObjectNode())));
    assertThrows(FrameException.class, () -> codec.encode(
        new Envelope(Protocol.VERSION, "HEARTBEAT", " ", "c", "t", M.createObjectNode())));
    assertThrows(FrameException.class, () -> codec.encode(
        new Envelope(Protocol.VERSION, "HEARTBEAT", "m", "c", null, M.createObjectNode())));
    assertThrows(FrameException.class, () -> codec.encode(
        new Envelope(Protocol.VERSION, "HEARTBEAT", "m", "c", "t", null)));
    assertThrows(FrameException.class, () -> codec.encode(null));
  }

  @Test void encodeRejectsOversizeEnvelope() throws Exception {
    ObjectNode p = M.createObjectNode().put("pad", "x".repeat(Protocol.MAX_FRAME_BYTES));
    Envelope e = new Envelope(Protocol.VERSION, "HEARTBEAT", "m-big", "c", "t", p);
    assertThrows(FrameException.class, () -> codec.encode(e));
  }

  @Test void unicodePayloadSurvivesRoundTrip() throws Exception {
    ObjectNode p = M.createObjectNode().put("title", "SIMULATION — Fire Drill 🔥 Tiếng Việt");
    Envelope e = new Envelope(Protocol.VERSION, "ALERT_PUSH", "m-u", "c-u",
        "2026-11-21T00:00:00Z", p);
    assertEquals(e, codec.decode(codec.encode(e)));
    ByteArrayOutputStreamHolder.writeAndRead(codec, e);
  }

  @Test void rejectsMalformedSentAtOnDecodeAndEncode() throws Exception {
    byte[] wire = mutated(n -> n.put("sentAt", "not-a-timestamp"));
    assertThrows(FrameException.class, () -> codec.decode(wire));
    Envelope bad = new Envelope(Protocol.VERSION, "HEARTBEAT", "m-bad", null,
        "2026-11-21 00:00:00", M.createObjectNode());
    assertThrows(FrameException.class, () -> codec.encode(bad));
  }

  /** Small helper kept local to avoid extra files. */
  static final class ByteArrayOutputStreamHolder {
    static void writeAndRead(FrameCodec codec, Envelope e) throws Exception {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      codec.writeFrame(out, e);
      assertEquals(e, codec.readFrame(new ByteArrayInputStream(out.toByteArray())));
    }
  }
}
