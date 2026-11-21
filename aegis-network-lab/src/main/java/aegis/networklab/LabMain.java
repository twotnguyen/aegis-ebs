package aegis.networklab;

import javax.net.ssl.SSLContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Lab CLI. Run via: {@code mvn -q -pl aegis-network-lab compile exec:java
 * -Dexec.mainClass=aegis.networklab.LabMain -Dexec.args="<command> [options]"}
 * Commands: probe | serve | discover | publish | subscribe | bench.
 * See src/main/resources/lab-guide.md for the full walkthrough.
 */
public final class LabMain {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) { usage(); System.exit(1); return; }
    Map<String, String> o = parseOpts(args);
    switch (args[0]) {
      case "probe" -> probe(o);
      case "serve" -> serve(o);
      case "discover" -> discover(o);
      case "publish" -> publish(o);
      case "subscribe" -> subscribe(o);
      case "bench" -> bench(o);
      default -> { usage(); System.exit(1); }
    }
  }

  private static void usage() {
    System.out.println("""
        AEGIS network lab (CMP180 Bài 7/8) — commands:
          probe      [--group G] [--port P] [--ttl T] [--iface N]
          serve      [--discovery-port 9999] [--tls-port 9443] [--server-id aegis-lab-server]
                     [--advertise-host 127.0.0.1] [--cert-dir target/lab-certs]
          discover   [--host 127.0.0.1] [--port 9999] [--timeout-ms 2000]
                     [--expect-server-id ID] [--cert-dir target/lab-certs]
          publish    --alert ID --revision R [--count K] [--interval-ms 500]
                     [--group G] [--port P] [--ttl T] [--iface N] [--targets host:port,...]
          subscribe  [--discovery-port 9999] [--host 127.0.0.1] [--cert-dir target/lab-certs]
                     [--tls-host H --tls-port P]  (explicit TLS instead of discovery)
                     [--group G] [--port P] [--ttl T] [--iface N]
          bench      [--subscribers 8] [--messages 100] [--payload-bytes 512]
                     [--report-dir target/lab-report] [--group G] [--port P] [--ttl T] [--iface N]
        """);
  }

  private static Map<String, String> parseOpts(String[] args) {
    Map<String, String> m = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      if (!args[i].startsWith("--")) continue;
      String key = args[i].substring(2);
      if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(key, args[++i]);
      else m.put(key, "true");
    }
    return m;
  }

  private static int intOpt(Map<String, String> o, String k, int def) {
    return o.containsKey(k) ? Integer.parseInt(o.get(k)) : def;
  }

  private static MulticastConfig mcast(Map<String, String> o) {
    return new MulticastConfig(
        o.getOrDefault("group", LabDefaults.MULTICAST_GROUP),
        intOpt(o, "port", LabDefaults.MULTICAST_PORT),
        intOpt(o, "ttl", LabDefaults.MULTICAST_TTL),
        o.get("iface"),
        !"false".equals(o.get("loop")));
  }

  private static Path certDir(Map<String, String> o) {
    return Path.of(o.getOrDefault("cert-dir", "target/lab-certs"));
  }

  private static void probe(Map<String, String> o) {
    MulticastSupport.ProbeResult r = MulticastSupport.probe(mcast(o));
    System.out.println("multicast available: " + r.available());
    System.out.println("interface: " + r.interfaceUsed());
    System.out.println("detail: " + r.detail());
    if (!r.available()) System.exit(3);
  }

  private static void serve(Map<String, String> o) throws Exception {
    String serverId = o.getOrDefault("server-id", LabDefaults.SERVER_ID);
    int tlsPort = intOpt(o, "tls-port", LabDefaults.TLS_PORT);
    int discoveryPort = intOpt(o, "discovery-port", LabDefaults.DISCOVERY_PORT);
    String advertiseHost = o.getOrDefault("advertise-host", LabDefaults.SERVER_HOST);
    Path dir = certDir(o);
    LabKeytool.LabKeystores ks = Files.exists(dir.resolve("lab-server.p12"))
        ? new LabKeytool.LabKeystores(dir.resolve("lab-server.p12"), dir.resolve("lab-trust.p12"), LabKeytool.LAB_PASSWORD)
        : LabKeytool.generate(dir, serverId);
    AlertDirectory directory = new AlertDirectory();
    directory.put(new AlertContent("demo-alert", 1, "AEGIS lab demo alert",
        "Simulation/training content served over verified TLS.", "HIGH", Instant.now().toString()));
    SSLContext serverCtx = LabTlsContexts.server(ks.serverKeystore(), ks.password());
    LabTlsServer tls = new LabTlsServer(serverCtx, serverId, directory);
    int actualTls = tls.start(tlsPort);
    DiscoveryServer discovery = new DiscoveryServer(new DiscoveryServer.Config(
        discoveryPort, advertiseHost, actualTls, serverId));
    discovery.start();
    System.out.println("[serve] discovery UDP " + discoveryPort + " (advertises " + advertiseHost + ":" + actualTls + ")");
    System.out.println("[serve] TLS endpoint " + actualTls + " serverId=" + serverId);
    System.out.println("[serve] certs " + dir.toAbsolutePath() + " (LAB ONLY, public password)");
    System.out.println("[serve] seeded alert: demo-alert rev 1 — Ctrl-C to stop");
    CountDownLatch stop = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { discovery.close(); tls.close(); stop.countDown(); }));
    stop.await();
  }

  private static void discover(Map<String, String> o) throws Exception {
    Path trust = certDir(o).resolve("lab-trust.p12");
    if (!Files.exists(trust)) {
      System.err.println("no trust store at " + trust + " — run 'serve' first or pass --cert-dir");
      System.exit(1); return;
    }
    SSLContext pinned = LabTlsContexts.client(trust, LabKeytool.LAB_PASSWORD);
    DiscoveryClient client = new DiscoveryClient(new DiscoveryClient.Config(
        o.getOrDefault("host", LabDefaults.SERVER_HOST),
        intOpt(o, "port", LabDefaults.DISCOVERY_PORT),
        intOpt(o, "timeout-ms", 2000),
        o.get("expect-server-id")));
    try {
      DiscoveryClient.TrustedServer t = client.discoverTrusted(pinned);
      System.out.println("TRUSTED (UDP nonce echoed + pinned TLS + CN==serverId): "
          + t.serverHost() + ":" + t.tlsPort() + " serverId=" + t.serverId() + " nonce=" + t.nonce());
    } catch (DiscoveryRejectedException ex) {
      System.out.println("REJECTED: " + ex.getMessage());
      System.exit(2);
    }
  }

  private static void publish(Map<String, String> o) throws Exception {
    String alertId = o.get("alert");
    if (alertId == null || !o.containsKey("revision")) {
      System.err.println("publish needs --alert ID --revision R"); System.exit(1); return;
    }
    long revision = Long.parseLong(o.get("revision"));
    int count = intOpt(o, "count", 1);
    long interval = intOpt(o, "interval-ms", 500);
    MulticastConfig cfg = mcast(o);
    MulticastSupport.ProbeResult probe = MulticastSupport.probe(cfg);
    HintPublisher pub;
    if (probe.available()) {
      pub = new MulticastHintPublisher(cfg);
      System.out.println("[publish] MULTICAST " + cfg.group() + ":" + cfg.port() + " ttl=" + cfg.ttl());
    } else if (o.containsKey("targets")) {
      UnicastHintPublisher up = new UnicastHintPublisher();
      for (String t : o.get("targets").split(",")) {
        String[] hp = t.split(":");
        up.addTarget(new java.net.InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
      }
      pub = up;
      System.out.println("[publish] UNICAST FALLBACK to " + up.targetCount() + " targets (" + probe.detail() + ")");
    } else {
      System.err.println("[publish] multicast unavailable (" + probe.detail()
          + ") and no --targets host:port,... given for unicast fallback");
      System.exit(3); return;
    }
    try (pub) {
      for (int i = 0; i < count; i++) {
        pub.publish(new AlertHint(alertId, revision + i, LabDefaults.SERVER_ID));
        if (i + 1 < count) Thread.sleep(interval);
      }
      System.out.println("[publish] sent " + pub.packetsSent() + " datagrams, " + pub.bytesSent() + " bytes");
    }
  }

  private static void subscribe(Map<String, String> o) throws Exception {
    Path trust = certDir(o).resolve("lab-trust.p12");
    if (!Files.exists(trust)) {
      System.err.println("no trust store at " + trust + " — run 'serve' first or pass --cert-dir");
      System.exit(1); return;
    }
    SSLContext pinned = LabTlsContexts.client(trust, LabKeytool.LAB_PASSWORD);
    LabTlsClient tls;
    if (o.containsKey("tls-host") && o.containsKey("tls-port")) {
      tls = LabTlsClient.connect(o.get("tls-host"), Integer.parseInt(o.get("tls-port")), pinned);
    } else {
      DiscoveryClient dc = new DiscoveryClient(new DiscoveryClient.Config(
          o.getOrDefault("host", LabDefaults.SERVER_HOST),
          intOpt(o, "discovery-port", LabDefaults.DISCOVERY_PORT),
          intOpt(o, "timeout-ms", 3000), o.get("expect-server-id")));
      DiscoveryClient.TrustedServer t = dc.discoverTrusted(pinned);
      System.out.println("[subscribe] trusted endpoint via discovery: " + t.serverHost() + ":" + t.tlsPort());
      tls = LabTlsClient.connectVerified(t.serverHost(), t.tlsPort(), pinned, t.serverId(), t.nonce());
    }
    VerifiedAlertSubscriber vas = new VerifiedAlertSubscriber(tls, d ->
        System.out.println("[VERIFIED via TLS] " + d.alert().alertId() + " rev " + d.alert().revision()
            + " \"" + d.alert().title() + "\" (hint from " + d.triggeringHint().serverId() + ")"),
        (h, ex) -> System.out.println("[fetch FAILED for hint " + h.alertId() + " rev " + h.revision()
            + ": " + ex + " — nothing displayed, hint stays advisory]"));
    FallbackHintBus bus = new FallbackHintBus(mcast(o), 1, vas);
    System.out.println("[subscribe] hint channel mode=" + bus.mode() + " — " + bus.probe().detail());
    System.out.println("[subscribe] waiting for advisory hints; only TLS-verified content is displayed. Ctrl-C to stop");
    CountDownLatch stop = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { bus.close(); try { tls.close(); } catch (Exception ignore) { } stop.countDown(); }));
    stop.await();
  }

  private static void bench(Map<String, String> o) throws Exception {
    FanoutBenchmark.Config cfg = new FanoutBenchmark.Config(
        intOpt(o, "subscribers", 8), intOpt(o, "messages", 100),
        intOpt(o, "payload-bytes", 512), mcast(o));
    FanoutBenchmark.Result result = new FanoutBenchmark(cfg).run();
    Path dir = Path.of(o.getOrDefault("report-dir", "target/lab-report"));
    BenchmarkReport.ReportPaths paths = BenchmarkReport.write(result, dir);
    System.out.println(BenchmarkReport.toMarkdown(result));
    System.out.println("[bench] report JSON: " + paths.json().toAbsolutePath());
    System.out.println("[bench] report MD:   " + paths.markdown().toAbsolutePath());
  }
}
