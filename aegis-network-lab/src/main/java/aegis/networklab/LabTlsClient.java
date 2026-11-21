package aegis.networklab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.FrameException;
import aegis.protocol.MessageType;
import aegis.protocol.Protocol;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * TLS client for the trusted channel. Verification on connect:
 * (1) pinned trust store + HTTPS endpoint identification (hostname/SAN),
 * (2) certificate CN == expected serverId,
 * (3) EBSP HELLO{nonce} → SERVER_HELLO must echo the SAME nonce and serverId.
 * Only after all three does {@link #fetchAlert} become usable — this is the gate that makes a
 * spoofed UDP discovery reply harmless.
 */
public final class LabTlsClient implements AutoCloseable {

  private final SSLSocket socket;
  private final FrameCodec codec;
  private final ObjectMapper mapper = new ObjectMapper();
  private final String serverId;

  private LabTlsClient(SSLSocket socket, String serverId) {
    this.socket = socket;
    this.serverId = serverId;
    this.codec = new FrameCodecImpl(mapper);
  }

  /** Connect with internal consistency checks (fresh nonce; CN must equal claimed serverId). */
  public static LabTlsClient connect(String host, int port, SSLContext pinnedTls)
      throws IOException, LabCodecException, FrameException, DiscoveryRejectedException {
    return connectInternal(host, port, pinnedTls, null, null);
  }

  /** Connect bound to a discovery exchange: CN must equal expectedServerId, echo must equal nonce. */
  public static LabTlsClient connectVerified(String host, int port, SSLContext pinnedTls,
                                             String expectedServerId, String nonce)
      throws IOException, LabCodecException, FrameException, DiscoveryRejectedException {
    if (expectedServerId == null || nonce == null)
      throw new IllegalArgumentException("connectVerified needs expectedServerId and nonce");
    return connectInternal(host, port, pinnedTls, expectedServerId, nonce);
  }

  private static LabTlsClient connectInternal(String host, int port, SSLContext pinnedTls,
                                              String expectedServerId, String nonce)
      throws IOException, LabCodecException, FrameException, DiscoveryRejectedException {
    SSLSocket s = (SSLSocket) pinnedTls.getSocketFactory().createSocket();
    boolean ok = false;
    try {
      s.connect(new InetSocketAddress(host, port), 5000);
      s.setSoTimeout(5000);
      SSLParameters params = s.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS"); // hostname/SAN verification
      s.setSSLParameters(params);
      s.startHandshake(); // pinned trust store enforced here

      Certificate[] chain = s.getSession().getPeerCertificates();
      if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf))
        throw new DiscoveryRejectedException("no X.509 peer certificate");
      String cn = CertIdentity.commonName(leaf);

      ObjectMapper mapper = new ObjectMapper();
      FrameCodec codec = new FrameCodecImpl(mapper);
      String helloNonce = nonce != null ? nonce
          : java.util.HexFormat.of().formatHex(new java.security.SecureRandom().generateSeed(16));
      ObjectNode helloPayload = mapper.createObjectNode();
      helloPayload.put("nonce", helloNonce);
      codec.writeFrame(s.getOutputStream(), new Envelope(Protocol.VERSION, MessageType.HELLO.name(),
          UUID.randomUUID().toString(), null, Instant.now().toString(), helloPayload));
      Envelope reply = codec.readFrame(s.getInputStream());
      if (!MessageType.SERVER_HELLO.name().equals(reply.type()))
        throw new DiscoveryRejectedException("expected SERVER_HELLO, got " + reply.type());
      JsonNode p = reply.payload();
      String echoedNonce = text(p, "nonce");
      String claimedId = text(p, "serverId");
      JsonNode v = p.get("version");
      if (v == null || !v.isInt() || v.intValue() != Protocol.VERSION)
        throw new DiscoveryRejectedException("SERVER_HELLO version mismatch");
      if (!helloNonce.equals(echoedNonce))
        throw new DiscoveryRejectedException("SERVER_HELLO did not echo the discovery nonce"
            + " — TLS session not bound to discovery exchange");
      if (!cn.equals(claimedId))
        throw new DiscoveryRejectedException("certificate CN " + cn + " != claimed serverId " + claimedId);
      if (expectedServerId != null && !cn.equals(expectedServerId))
        throw new DiscoveryRejectedException("certificate CN " + cn
            + " != expected serverId " + expectedServerId + " (discovery reply pointed elsewhere)");
      LabTlsClient client = new LabTlsClient(s, claimedId);
      ok = true;
      return client;
    } finally {
      if (!ok) {
        try { s.close(); } catch (IOException ignore) { }
      }
    }
  }

  public String serverId() { return serverId; }

  /** Fetch authoritative content over the verified TLS session. Empty = server has no such alert. */
  public Optional<AlertContent> fetchAlert(String alertId) throws IOException, FrameException {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("alertId", alertId);
    String requestId = UUID.randomUUID().toString();
    codec.writeFrame(socket.getOutputStream(), new Envelope(Protocol.VERSION,
        MessageType.SYNC_REQUEST.name(), requestId, null, Instant.now().toString(), payload));
    Envelope reply = codec.readFrame(socket.getInputStream());
    if (MessageType.ERROR.name().equals(reply.type()))
      throw new IOException("server ERROR: " + reply.payload());
    if (!MessageType.SYNC_RESULT.name().equals(reply.type()))
      throw new IOException("expected SYNC_RESULT, got " + reply.type());
    if (!requestId.equals(reply.correlationId()))
      throw new IOException("SYNC_RESULT correlationId mismatch");
    JsonNode p = reply.payload();
    if (!p.path("found").asBoolean(false)) return Optional.empty();
    JsonNode a = p.get("alert");
    if (a == null || !a.isObject()) throw new IOException("SYNC_RESULT missing alert object");
    return Optional.of(new AlertContent(
        text(a, "alertId"), a.path("revision").asLong(-1), text(a, "title"), text(a, "body"),
        text(a, "priority"), text(a, "issuedAt")));
  }

  private static String text(JsonNode n, String field) throws IOException {
    JsonNode v = n.get(field);
    if (v == null || !v.isTextual()) throw new IOException("missing textual field: " + field);
    return v.asText();
  }

  @Override
  public void close() throws IOException { socket.close(); }
}
