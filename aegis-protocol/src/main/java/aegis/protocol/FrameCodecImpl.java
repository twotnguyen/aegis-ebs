package aegis.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * EBSP/2.0 codec: uint32 big-endian length L (1..65536) + L bytes strict UTF-8 JSON.
 * Oversized lengths are rejected BEFORE any body allocation; partial reads are
 * completed via readFully; coalesced frames are consumed exactly (no over-read).
 */
public final class FrameCodecImpl implements FrameCodec {

  private final ObjectMapper mapper;

  public FrameCodecImpl() { this(new ObjectMapper()); }

  public FrameCodecImpl(ObjectMapper mapper) { this.mapper = mapper; }

  @Override
  public byte[] encode(Envelope e) throws FrameException {
    validate(e);
    byte[] body;
    try {
      body = mapper.writeValueAsBytes(e);
    } catch (JsonProcessingException ex) {
      throw new FrameException("cannot serialize envelope", ex);
    }
    checkLength(body.length);
    byte[] frame = new byte[Protocol.PREFIX_BYTES + body.length];
    writeLength(frame, body.length);
    System.arraycopy(body, 0, frame, Protocol.PREFIX_BYTES, body.length);
    return frame;
  }

  @Override
  public Envelope decode(byte[] frame) throws FrameException {
    if (frame == null) throw new FrameException("null frame");
    if (frame.length < Protocol.PREFIX_BYTES + 1) {
      throw new FrameException("frame too short: " + frame.length + " bytes");
    }
    int len = readLength(frame, 0);
    checkLength(len); // reject before allocating/copying the body
    if (frame.length != Protocol.PREFIX_BYTES + len) {
      throw new FrameException("length prefix " + len + " does not match actual body "
          + (frame.length - Protocol.PREFIX_BYTES));
    }
    byte[] body = new byte[len];
    System.arraycopy(frame, Protocol.PREFIX_BYTES, body, 0, len);
    return decodeBody(body);
  }

  @Override
  public Envelope readFrame(InputStream in) throws FrameException, IOException {
    DataInputStream din = (in instanceof DataInputStream d) ? d : new DataInputStream(in);
    byte[] header = new byte[Protocol.PREFIX_BYTES];
    din.readFully(header); // handles partial header reads (e.g. 4 x 1-byte chunks)
    int len = readLength(header, 0);
    checkLength(len); // CHẶN oversize TRƯỚC khi cấp phát body
    byte[] body = new byte[len];
    din.readFully(body); // handles partial body reads; leaves coalesced frames untouched
    return decodeBody(body);
  }

  @Override
  public void writeFrame(OutputStream out, Envelope e) throws FrameException, IOException {
    out.write(encode(e));
    out.flush();
  }

  // ---- internals ----

  private static void checkLength(int len) throws FrameException {
    if (len < 1 || len > Protocol.MAX_FRAME_BYTES) {
      throw new FrameException("frame length out of range 1.." + Protocol.MAX_FRAME_BYTES + ": " + len);
    }
  }

  private static int readLength(byte[] b, int off) {
    return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
        | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
  }

  private static void writeLength(byte[] b, int len) {
    b[0] = (byte) (len >>> 24);
    b[1] = (byte) (len >>> 16);
    b[2] = (byte) (len >>> 8);
    b[3] = (byte) len;
  }

  private Envelope decodeBody(byte[] body) throws FrameException {
    String json;
    try {
      json = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(body))
          .toString();
    } catch (CharacterCodingException ex) {
      throw new FrameException("invalid UTF-8 in frame body", ex);
    }

    JsonNode root;
    try (JsonParser p = mapper.getFactory().createParser(json)) {
      root = mapper.readTree(p);
      if (root == null || !root.isObject()) {
        throw new FrameException("frame body must be a JSON object");
      }
      if (p.nextToken() != null) {
        throw new FrameException("trailing tokens after JSON object");
      }
    } catch (JsonProcessingException ex) {
      throw new FrameException("invalid JSON in frame body", ex);
    } catch (IOException ex) {
      throw new FrameException("cannot parse frame body", ex);
    }

    JsonNode vn = root.get("version");
    if (vn == null || !vn.isInt()) throw new FrameException("missing or non-integer version");
    if (vn.intValue() != Protocol.VERSION) {
      throw new FrameException("unsupported protocol version: " + vn.intValue()
          + " (expected " + Protocol.VERSION + ")");
    }

    String type = requireText(root, "type");
    try {
      MessageType.valueOf(type);
    } catch (IllegalArgumentException ex) {
      throw new FrameException("unknown message type: " + type, ex);
    }

    String messageId = requireText(root, "messageId");
    String sentAt = requireText(root, "sentAt");
    String correlationId = optionalText(root, "correlationId");
    JsonNode payload = root.get("payload");
    if (payload == null || payload.isNull()) throw new FrameException("missing payload");

    return new Envelope(Protocol.VERSION, type, messageId, correlationId, sentAt, payload);
  }

  private static String requireText(JsonNode root, String field) throws FrameException {
    JsonNode n = root.get(field);
    if (n == null || !n.isTextual() || n.asText().isBlank()) {
      throw new FrameException("missing or blank field: " + field);
    }
    return n.asText();
  }

  private static String optionalText(JsonNode root, String field) throws FrameException {
    JsonNode n = root.get(field);
    if (n == null || n.isNull()) return null;
    if (!n.isTextual() || n.asText().isBlank()) {
      throw new FrameException("invalid field: " + field);
    }
    return n.asText();
  }

  private static void validate(Envelope e) throws FrameException {
    if (e == null) throw new FrameException("null envelope");
    if (e.version() != Protocol.VERSION) {
      throw new FrameException("unsupported protocol version: " + e.version());
    }
    if (e.type() == null || e.type().isBlank()) throw new FrameException("missing type");
    try {
      MessageType.valueOf(e.type());
    } catch (IllegalArgumentException ex) {
      throw new FrameException("unknown message type: " + e.type(), ex);
    }
    if (e.messageId() == null || e.messageId().isBlank()) throw new FrameException("missing messageId");
    if (e.sentAt() == null || e.sentAt().isBlank()) throw new FrameException("missing sentAt");
    if (e.correlationId() != null && e.correlationId().isBlank()) {
      throw new FrameException("blank correlationId");
    }
    if (e.payload() == null || e.payload().isNull()) throw new FrameException("missing payload");
  }
}
