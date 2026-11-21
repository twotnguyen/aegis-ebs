package aegis.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Round-trips every message in Plan/protocol_examples.json (copied to test resources). */
class FrameCodecFixtureTest {

  private final FrameCodec codec = new FrameCodecImpl();
  private static final ObjectMapper M = new ObjectMapper();

  static Stream<Arguments> fixtures() throws Exception {
    try (InputStream in = FrameCodecFixtureTest.class.getResourceAsStream("/protocol_examples.json")) {
      assertNotNull(in, "fixture resource missing");
      JsonNode root = M.readTree(in);
      assertEquals("EBSP/2.0", root.get("protocol").asText());
      List<Arguments> args = new ArrayList<>();
      JsonNode messages = root.get("messages");
      for (int i = 0; i < messages.size(); i++) {
        args.add(Arguments.of(i, messages.get(i)));
      }
      assertEquals(5, args.size(), "expected 5 fixture messages");
      return args.stream();
    }
  }

  static Envelope fromFixture(JsonNode n) {
    return new Envelope(
        n.get("version").intValue(),
        n.get("type").asText(),
        n.get("messageId").asText(),
        n.hasNonNull("correlationId") ? n.get("correlationId").asText() : null,
        n.get("sentAt").asText(),
        n.get("payload"));
  }

  @ParameterizedTest(name = "fixture[{0}] {1}")
  @MethodSource("fixtures")
  void encodeDecodeRoundTrip(int idx, JsonNode node) throws Exception {
    Envelope original = fromFixture(node);
    byte[] wire = codec.encode(original);
    Envelope decoded = codec.decode(wire);
    assertEquals(original.version(), decoded.version());
    assertEquals(original.type(), decoded.type());
    assertEquals(original.messageId(), decoded.messageId());
    assertEquals(original.correlationId(), decoded.correlationId());
    assertEquals(original.sentAt(), decoded.sentAt());
    assertEquals(original.payload(), decoded.payload());
    assertEquals(original, decoded);
  }

  @ParameterizedTest(name = "fixture[{0}] stream round-trip")
  @MethodSource("fixtures")
  void writeReadRoundTrip(int idx, JsonNode node) throws Exception {
    Envelope original = fromFixture(node);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    codec.writeFrame(out, original);
    Envelope decoded = codec.readFrame(new ByteArrayInputStream(out.toByteArray()));
    assertEquals(original, decoded);
  }

  @ParameterizedTest(name = "fixture[{0}] wire format")
  @MethodSource("fixtures")
  void wireFormatMatchesContract(int idx, JsonNode node) throws Exception {
    Envelope original = fromFixture(node);
    byte[] wire = codec.encode(original);
    int declared = ByteBuffer.wrap(wire, 0, Protocol.PREFIX_BYTES).getInt();
    assertEquals(wire.length - Protocol.PREFIX_BYTES, declared, "BE prefix must equal body length");
    assertTrue(declared >= 1 && declared <= Protocol.MAX_FRAME_BYTES);
    byte[] body = new byte[declared];
    System.arraycopy(wire, Protocol.PREFIX_BYTES, body, 0, declared);
    JsonNode parsed = M.readTree(new String(body, StandardCharsets.UTF_8));
    assertTrue(parsed.isObject());
    assertEquals(Protocol.VERSION, parsed.get("version").intValue());
    assertEquals(original.type(), parsed.get("type").asText());
    assertDoesNotThrow(() -> MessageType.valueOf(parsed.get("type").asText()));
    assertEquals(original.payload(), parsed.get("payload"));
  }

  @ParameterizedTest(name = "fixture[{0}] fragmented + coalesced")
  @MethodSource("fixtures")
  void allFixturesCoalescedAndFragmented(int idx, JsonNode node) throws Exception {
    // Build a burst of ALL fixtures, deliver in 5-byte chunks, read back in order.
    try (InputStream in = FrameCodecFixtureTest.class.getResourceAsStream("/protocol_examples.json")) {
      JsonNode messages = M.readTree(in).get("messages");
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      List<Envelope> expected = new ArrayList<>();
      for (JsonNode n : messages) {
        Envelope e = fromFixture(n);
        expected.add(e);
        buf.write(codec.encode(e));
      }
      InputStream chunked = new FrameCodecStreamTest.ChunkedInputStream(buf.toByteArray(), 5);
      for (Envelope e : expected) assertEquals(e, codec.readFrame(chunked));
      assertEquals(-1, chunked.read());
    }
  }
}
