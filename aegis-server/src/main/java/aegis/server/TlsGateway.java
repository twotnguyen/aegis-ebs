package aegis.server;

import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.FrameException;
import aegis.protocol.Priority;
import aegis.protocol.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.security.KeyStore;
import java.time.Clock;
import java.util.concurrent.Executor;

/**
 * TLS gateway (EBSP/2.0 primary channel, default port 9443). Server identity
 * comes from a PKCS12 keystore (scripts/gen-server-cert.sh generates the
 * self-signed demo cert + client truststore). One reader thread per connection
 * (virtual threads); all writes go through the session's bounded writer queue.
 */
public final class TlsGateway {
  private static final Logger LOG = LoggerFactory.getLogger(TlsGateway.class);

  private final ServerConfig config;
  private final SessionRegistry sessions;
  private final MessageRouter router;
  private final Executor executor;
  private final Clock clock;
  private final AuditService audit;
  private final MetricsRegistry metrics;
  private final StatusNotifier status;
  private final FrameCodec codec = new FrameCodecImpl();
  private volatile SSLServerSocket serverSocket;
  private volatile boolean running = false;

  public TlsGateway(ServerConfig config, SessionRegistry sessions, MessageRouter router,
                    Executor executor, Clock clock, AuditService audit, MetricsRegistry metrics,
                    StatusNotifier status) {
    this.config = config;
    this.sessions = sessions;
    this.router = router;
    this.executor = executor;
    this.clock = clock;
    this.audit = audit;
    this.metrics = metrics;
    this.status = status;
  }

  /** Builds an SSLContext from the configured PKCS12/JKS keystore. */
  public static SSLContext sslContext(ServerConfig config) throws Exception {
    KeyStore ks = KeyStore.getInstance(config.keystore().toString().endsWith(".p12") ? "PKCS12" : "JKS");
    try (InputStream in = new FileInputStream(config.keystore().toFile())) {
      ks.load(in, config.keystorePassword().toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, config.keystorePassword().toCharArray());
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), null, null);
    return ctx;
  }

  public void start() throws Exception {
    SSLContext ctx = sslContext(config);
    SSLServerSocketFactory factory = ctx.getServerSocketFactory();
    serverSocket = (SSLServerSocket) factory.createServerSocket(config.tlsPort());
    serverSocket.setNeedClientAuth(false); // server-auth TLS; clients verify via truststore
    running = true;
    executor.execute(this::acceptLoop);
    LOG.info("EBSP/2.0 TLS gateway listening on port {}", serverSocket.getLocalPort());
  }

  public int port() {
    SSLServerSocket s = serverSocket;
    return s == null ? config.tlsPort() : s.getLocalPort();
  }

  private void acceptLoop() {
    while (running) {
      try {
        SSLSocket socket = (SSLSocket) serverSocket.accept();
        // S-07: hard connection cap (pre-auth sessions included) — excess
        // sockets are dropped immediately instead of lingering unauthenticated.
        if (sessions.size() >= config.maxConnections()) {
          LOG.warn("connection cap {} reached; rejecting {}", config.maxConnections(),
              socket.getRemoteSocketAddress());
          socket.close();
          continue;
        }
        socket.setUseClientMode(false);
        socket.setTcpNoDelay(true);
        // S-07: pre-auth read timeout; cleared once the session authenticates
        // (authenticated liveness is owned by the heartbeat sweep).
        socket.setSoTimeout((int) Math.min(config.preAuthTimeoutMs(), Integer.MAX_VALUE));
        ClientSession session = new ClientSession(Json.uuid(), socket, codec,
            config.writerQueueCapacity(), clock, executor,
            this::onSessionClosed, this::onSlowClient);
        sessions.register(session);
        executor.execute(() -> readLoop(session));
      } catch (IOException e) {
        if (running) LOG.warn("TLS accept failed: {}", e.toString());
        return;
      }
    }
  }

  private void readLoop(ClientSession session) {
    try {
      while (!session.isClosed()) {
        Envelope env;
        try {
          env = codec.readFrame(session.input());
        } catch (java.net.SocketTimeoutException e) {
          // S-07: an idle pre-auth connection hit the read timeout -> drop it.
          if (!session.isAuthenticated()) {
            preAuthExpired(session, "read timeout");
            return;
          }
          continue; // authenticated sessions keep soTimeout=0; defensive only
        }
        router.route(session, env);
        if (session.isAuthenticated()) {
          // authenticated: liveness belongs to the heartbeat sweep, not a read
          // timeout (a mid-frame timeout could corrupt the stream).
          if (session.socket().getSoTimeout() != 0) session.socket().setSoTimeout(0);
        } else if (java.time.Duration.between(session.createdAt(), clock.instant()).toMillis()
            >= config.preAuthTimeoutMs()) {
          // S-07: absolute pre-auth TTL — keepalive frames (HEARTBEAT/HELLO)
          // must NOT extend an unauthenticated connection's lifetime forever.
          preAuthExpired(session, "ttl");
          return;
        }
      }
    } catch (FrameException e) {
      // wire violation: tell the peer, then drop (oversize frames never allocate)
      try {
        session.send(Priority.CRITICAL, new Envelope(Protocol.VERSION, "ERROR", Json.uuid(), null,
            Json.ts(clock.instant()),
            Json.obj().put("code", "BAD_FRAME").put("message", String.valueOf(e.getMessage()))));
      } catch (RuntimeException ignored) {
        // session already gone
      }
    } catch (IOException e) {
      // EOF / reset: normal disconnect
    } finally {
      session.close();
    }
  }

  private void preAuthExpired(ClientSession session, String reason) {
    audit.append(null, "PRE_AUTH_EXPIRED", session.sessionId(), "DISCONNECTED", null,
        Json.obj().put("reason", reason).put("ttlMs", config.preAuthTimeoutMs()));
    LOG.info("pre-auth session expired session={} reason={}", session.sessionId(), reason);
    session.close();
  }

  private void onSessionClosed(ClientSession session) {
    boolean wasDevice = session.kind() == ClientSession.Kind.DEVICE;
    sessions.unregister(session);
    if (wasDevice) {
      status.connectivityChanged();
    }
  }

  private void onSlowClient(ClientSession session) {
    metrics.incSlowClientDisconnects();
    audit.append(session.kind() == ClientSession.Kind.DEVICE ? session.deviceId() : session.userId(),
        "SLOW_CLIENT_DISCONNECT", session.sessionId(), "DISCONNECTED", null,
        Json.obj().put("queueCapacity", config.writerQueueCapacity()));
    LOG.warn("slow client disconnected session={} device={}", session.sessionId(), session.deviceId());
    session.close();
  }

  public void stop() {
    running = false;
    try {
      SSLServerSocket s = serverSocket;
      if (s != null) s.close();
    } catch (IOException ignored) {
      // shutting down
    }
    sessions.closeAll();
  }
}
