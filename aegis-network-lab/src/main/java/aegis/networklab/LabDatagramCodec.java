package aegis.networklab;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import aegis.protocol.Protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Lab datagram codec: one strict-UTF-8 JSON object per UDP datagram, max
 * {@value #MAX_DATAGRAM_BYTES} bytes (below Ethernet MTU 1500 → no IP fragmentation, Bài 7.1.1).
 * Mirrors FrameCodecImpl strictness: reject malformed UTF-8, non-objects, trailing tokens,
 * missing/blank/wrong-typed fields and unknown fields BEFORE acting on a datagram.
 */
public final class LabDatagramCodec {
  public static final int MAX_DATAGRAM_BYTES = 1024;
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{8,128}");
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:-]{1,253}");
  private final ObjectMapper mapper;

  public LabDatagramCodec() { this(new ObjectMapper()); }
  public LabDatagramCodec(ObjectMapper mapper) { this.mapper = mapper; }

  // ---- encode ----
  public byte[] encode(DiscoverRequest r) throws LabCodecException {
    if (r == null) throw new LabCodecException("null request");
    if (!NONCE.matcher(r.nonce() == null ? "" : r.nonce()).matches())
      throw new LabCodecException("invalid nonce (need 8..128 chars of [A-Za-z0-9_-])");
    if (r.version() != Protocol.VERSION)
      throw new LabCodecException("unsupported version: " + r.version());
    var n = mapper.createObjectNode();
    n.put("type", DiscoverRequest.TYPE);
    n.put("version", r.version());
    n.put("nonce", r.nonce());
    return finish(n);
  }

  public byte[] encode(DiscoverResponse r) throws LabCodecException {
    if (r == null) throw new LabCodecException("null response");
    requireHost(r.serverHost());
    requirePort(r.tlsPort());
    requireNonce(r.nonce());
    requireId(r.serverId(), "serverId");
    var n = mapper.createObjectNode();
    n.put("type", DiscoverResponse.TYPE);
    n.put("serverHost", r.serverHost());
    n.put("tlsPort", r.tlsPort());
    n.put("nonce", r.nonce());
    n.put("serverId", r.serverId());
    return finish(n);
  }

  public byte[] encode(AlertHint h) throws LabCodecException {
    if (h == null) throw new LabCodecException("null hint");
    requireId(h.alertId(), "alertId");
    if (h.revision() < 1) throw new LabCodecException("revision must be >= 1: " + h.revision());
    requireId(h.serverId(), "serverId");
    var n = mapper.createObjectNode();
    n.put("type", AlertHint.TYPE);
    n.put("alertId", h.alertId());
    n.put("revision", h.revision());
    n.put("serverId", h.serverId());
    return finish(n);
  }

  // ---- decode ----
  public DiscoverRequest decodeRequest(byte[] datagram) throws LabCodecException {
    JsonNode root = parse(datagram);
    requireType(root, DiscoverRequest.TYPE);
    requireOnlyFields(root, "type", "version", "nonce");
    JsonNode v = root.get("version");
    if (v == null || !v.isInt()) throw new LabCodecException("missing or non-integer version");
    if (v.intValue() != Protocol.VERSION)
      throw new LabCodecException("unsupported version: " + v.intValue());
    String nonce = requireText(root, "nonce");
    if (!NONCE.matcher(nonce).matches()) throw new LabCodecException("invalid nonce format");
    return new DiscoverRequest(nonce, v.intValue());
  }

  public DiscoverResponse decodeResponse(byte[] datagram) throws LabCodecException {
    JsonNode root = parse(datagram);
    requireType(root, DiscoverResponse.TYPE);
    requireOnlyFields(root, "type", "serverHost", "tlsPort", "nonce", "serverId");
    String host = requireText(root, "serverHost");
    requireHost(host);
    JsonNode p = root.get("tlsPort");
    if (p == null || !p.isInt()) throw new LabCodecException("missing or non-integer tlsPort");
    requirePort(p.intValue());
    String nonce = requireText(root, "nonce");
    requireNonce(nonce);
    String serverId = requireText(root, "serverId");
    requireId(serverId, "serverId");
    return new DiscoverResponse(host, p.intValue(), nonce, serverId);
  }

  public AlertHint decodeHint(byte[] datagram) throws LabCodecException {
    JsonNode root = parse(datagram);
    requireType(root, AlertHint.TYPE);
    requireOnlyFields(root, "type", "alertId", "revision", "serverId");
    String alertId = requireText(root, "alertId");
    requireId(alertId, "alertId");
    JsonNode r = root.get("revision");
    if (r == null || !r.isIntegralNumber()) throw new LabCodecException("missing or non-integer revision");
    if (r.longValue() < 1) throw new LabCodecException("revision must be >= 1: " + r.longValue());
    String serverId = requireText(root, "serverId");
    requireId(serverId, "serverId");
    return new AlertHint(alertId, r.longValue(), serverId);
  }

  /** Peek the discriminator without full validation (for routing/probe filtering). */
  public String peekType(byte[] datagram) throws LabCodecException {
    return requireText(parse(datagram), "type");
  }

  // ---- internals ----
  private byte[] finish(JsonNode n) throws LabCodecException {
    byte[] body;
    try {
      body = mapper.writeValueAsBytes(n);
    } catch (JsonProcessingException ex) {
      throw new LabCodecException("cannot serialize datagram", ex);
    }
    if (body.length > MAX_DATAGRAM_BYTES)
      throw new LabCodecException("datagram too large: " + body.length + " > " + MAX_DATAGRAM_BYTES);
    return body;
  }

  private JsonNode parse(byte[] datagram) throws LabCodecException {
    if (datagram == null || datagram.length == 0) throw new LabCodecException("empty datagram");
    if (datagram.length > MAX_DATAGRAM_BYTES)
      throw new LabCodecException("datagram too large: " + datagram.length);
    String json;
    try {
      json = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(datagram)).toString();
    } catch (CharacterCodingException ex) {
      throw new LabCodecException("invalid UTF-8 in datagram", ex);
    }
    JsonNode root;
    try (JsonParser p = mapper.getFactory().createParser(json)) {
      root = mapper.readTree(p);
      if (root == null || !root.isObject()) throw new LabCodecException("datagram must be a JSON object");
      if (p.nextToken() != null) throw new LabCodecException("trailing tokens after JSON object");
    } catch (JsonProcessingException ex) {
      throw new LabCodecException("invalid JSON in datagram", ex);
    } catch (IOException ex) {
      throw new LabCodecException("cannot parse datagram", ex);
    }
    return root;
  }

  private static void requireOnlyFields(JsonNode root, String... allowed) throws LabCodecException {
    java.util.Set<String> ok = java.util.Set.of(allowed);
    java.util.Iterator<String> it = root.fieldNames();
    while (it.hasNext()) {
      String f = it.next();
      if (!ok.contains(f)) throw new LabCodecException("unknown field: " + f);
    }
  }

  private static void requireType(JsonNode root, String expected) throws LabCodecException {
    String t = requireText(root, "type");
    if (!expected.equals(t)) throw new LabCodecException("wrong type: " + t + " (expected " + expected + ")");
  }

  private static String requireText(JsonNode root, String field) throws LabCodecException {
    JsonNode n = root.get(field);
    if (n == null || !n.isTextual() || n.asText().isBlank())
      throw new LabCodecException("missing or blank field: " + field);
    return n.asText();
  }

  private static void requireNonce(String nonce) throws LabCodecException {
    if (nonce == null || !NONCE.matcher(nonce).matches())
      throw new LabCodecException("invalid nonce format");
  }

  private static void requireId(String id, String field) throws LabCodecException {
    if (id == null || !ID.matcher(id).matches())
      throw new LabCodecException("invalid " + field + " format");
  }

  private static void requireHost(String host) throws LabCodecException {
    if (host == null || !HOST.matcher(host).matches())
      throw new LabCodecException("invalid serverHost format");
  }

  private static void requirePort(int port) throws LabCodecException {
    if (port < 1 || port > 65535) throw new LabCodecException("port out of range: " + port);
  }
}
