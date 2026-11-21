package aegis.server;

import aegis.core.RetryPolicy;
import aegis.core.TargetResolver;
import aegis.persistence.AlertRepo;
import aegis.persistence.AuditRepo;
import aegis.persistence.Db;
import aegis.persistence.DeviceRepo;
import aegis.persistence.DeliveryRepo;
import aegis.persistence.MigrationRunner;
import aegis.persistence.RecoveryScanner;
import aegis.persistence.RoleGrantRepo;
import aegis.persistence.TransactionalPublisher;
import aegis.persistence.UserRepo;
import aegis.protocol.Envelope;
import aegis.protocol.Priority;
import aegis.protocol.Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the whole engine and owns its lifecycle:
 * migrate -&gt; seed -&gt; RecoveryScanner rebuild -&gt; WS feed + TLS gateway +
 * heartbeat/retry/status/expiry timers. Close stops accepting, drains sessions
 * and shuts the executors down; durable state survives for the next recovery.
 */
public final class AegisServer implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(AegisServer.class);

  private final ServerConfig config;
  private final Clock clock = Clock.systemUTC();
  private final Db db;
  private final SessionRegistry sessions = new SessionRegistry();
  private final MetricsRegistry metrics = new MetricsRegistry();
  private final ScheduledExecutorService timers;
  private final ExecutorService ioExecutor;
  private final FanoutEngine engine;
  private final TlsGateway gateway;
  private final StatusFeedServer feedServer;
  private final StatusFeedService status;
  private final ServerStore store;
  private final AuditService audit;
  private volatile boolean started = false;

  public AegisServer(ServerConfig config) throws Exception {
    this.config = config;
    this.db = new Db(config.dbFile());
    new MigrationRunner(db).migrate();

    UserRepo users = new UserRepo(db, clock);
    DeviceRepo devices = new DeviceRepo(db, clock);
    AlertRepo alerts = new AlertRepo(db, clock);
    DeliveryRepo deliveries = new DeliveryRepo(db, clock);
    AuditRepo auditRepo = new AuditRepo(db, clock);
    RoleGrantRepo grantRepo = new RoleGrantRepo(db, clock);

    PasswordHasher hasher = new Argon2idHasher(config.argon2Iterations(), config.argon2MemoryKib(),
        config.argon2Parallelism());
    this.store = new ServerStore(db, clock, alerts, deliveries);
    if (config.seedDemo()) {
      store.seedWorld(config.buildingId(), config.devicesPerZone(), hasher);
    }
    this.audit = new AuditService(auditRepo);
    this.status = new StatusFeedService(config.buildingId(), store, alerts, sessions, metrics, clock);
    status.init();

    ThreadFactory timerFactory = new ThreadFactory() {
      private final AtomicInteger n = new AtomicInteger();
      @Override public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "aegis-timer-" + n.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    };
    this.timers = Executors.newScheduledThreadPool(2, timerFactory);
    this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    RetryPolicy retryPolicy = new RetryPolicy(Math.max(1, config.maxRetries()), config.retryBaseDelayMs());
    this.engine = new FanoutEngine(alerts, deliveries, sessions, retryPolicy, timers, clock,
        metrics, audit, status);
    engine.start(ioExecutor);

    TransactionalPublisher publisher = new TransactionalPublisher(db, clock, engine);
    TargetResolver targets = TargetResolver.of(store.deviceLocations());
    AlertService alertService = new AlertService(alerts, deliveries, store, publisher, engine,
        targets, audit, metrics, status, clock);
    IncidentService incidents = new IncidentService(store, audit, sessions, clock);
    SyncService sync = new SyncService(store, engine, clock);
    AuthService auth = new AuthService(users, devices, hasher);
    RbacService rbac = new RbacService(grantRepo);
    MessageRouter router = new MessageRouter(config, auth, rbac, alertService, incidents, sync,
        engine, sessions, store, alerts, deliveries, audit, status, clock);

    this.gateway = new TlsGateway(config, sessions, router, ioExecutor, clock, audit, metrics, status);
    this.feedServer = new StatusFeedServer(config.wsPort(), status, ioExecutor);
  }

  public void start() throws Exception {
    // (8) crash recovery: rebuild the scheduler from durable deliveries BEFORE
    // accepting connections, so reconnecting devices find their queue intact.
    int recovered = new RecoveryScanner(db).recover(engine::recoverEnqueue);
    if (recovered > 0) {
      audit.append(null, "RECOVERY", config.buildingId(), "OK", null,
          Json.obj().put("recoveredDeliveries", recovered));
      LOG.info("recovered {} active deliveries from durable state", recovered);
    }
    feedServer.start();
    gateway.start();

    long hb = config.heartbeatIntervalMs();
    timers.scheduleAtFixedRate(this::sweepHeartbeats, hb, hb, TimeUnit.MILLISECONDS);
    timers.scheduleAtFixedRate(this::broadcastHeartbeat, hb, hb, TimeUnit.MILLISECONDS);
    timers.scheduleAtFixedRate(status::broadcastDelta, config.statusBatchMs(), config.statusBatchMs(),
        TimeUnit.MILLISECONDS);
    timers.scheduleAtFixedRate(this::expireAlerts, 1000, 1000, TimeUnit.MILLISECONDS);
    timers.scheduleAtFixedRate(this::flushMetrics, 30_000, 30_000, TimeUnit.MILLISECONDS);
    started = true;
    LOG.info("aegis-server up: TLS {} WS {} building {} db {}",
        gateway.port(), feedServer.port(), config.buildingId(), config.dbFile());
  }

  private void sweepHeartbeats() {
    try {
      List<ClientSession> suspects = sessions.sweep(clock, config.heartbeatIntervalMs(),
          config.heartbeatMaxMissed());
      for (ClientSession s : suspects) {
        audit.append(s.kind() == ClientSession.Kind.DEVICE ? s.deviceId() : s.userId(),
            "HEARTBEAT_SUSPECT", s.sessionId(), "SUSPECT", null,
            Json.obj().put("missed", config.heartbeatMaxMissed()));
        LOG.info("heartbeat suspect session={} device={}", s.sessionId(), s.deviceId());
        s.close();
      }
    } catch (RuntimeException e) {
      LOG.warn("heartbeat sweep failed", e);
    }
  }

  private void broadcastHeartbeat() {
    try {
      Envelope hb = new Envelope(Protocol.VERSION, "HEARTBEAT", Json.uuid(), null,
          Json.ts(clock.instant()),
          Json.obj().put("intervalMs", config.heartbeatIntervalMs()));
      for (ClientSession s : sessions.all()) {
        if (s.isAuthenticated() && !s.isClosed()) {
          s.send(Priority.CRITICAL, hb);
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("heartbeat broadcast failed", e);
    }
  }

  private void expireAlerts() {
    try {
      List<String> expired = store.expireOverdue();
      for (String alertId : expired) {
        engine.cancelAlert(alertId);
        audit.append(null, "ALERT_EXPIRED", alertId, "EXPIRED", null);
      }
      if (!expired.isEmpty()) {
        status.alertsChanged();
      }
    } catch (Exception e) {
      LOG.warn("expiry sweep failed", e);
    }
  }

  private void flushMetrics() {
    try {
      store.insertMetricSample("delivery_latency_p50", metrics.p50(), "ms", null);
      store.insertMetricSample("delivery_latency_p95", metrics.p95(), "ms", null);
      store.insertMetricSample("delivery_latency_p99", metrics.p99(), "ms", null);
      store.insertMetricSample("transport_retries", metrics.retryCount(), "count", null);
    } catch (Exception e) {
      LOG.warn("metrics flush failed", e);
    }
  }

  public int tlsPort() { return gateway.port(); }
  public int wsPort() { return feedServer.port(); }
  public SessionRegistry sessions() { return sessions; }
  public MetricsRegistry metrics() { return metrics; }
  public StatusFeedService statusFeed() { return status; }
  public FanoutEngine engine() { return engine; }
  public ServerStore store() { return store; }
  public Db db() { return db; }
  public ServerConfig config() { return config; }
  public boolean isStarted() { return started; }

  @Override
  public void close() {
    started = false;
    gateway.stop();
    feedServer.stop();
    engine.shutdown();
    timers.shutdownNow();
    ioExecutor.shutdown();
    try {
      ioExecutor.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    sessions.closeAll();
  }
}
