package aegis.networklab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import aegis.protocol.Envelope;
import aegis.protocol.FrameCodec;
import aegis.protocol.FrameCodecImpl;
import aegis.protocol.MessageType;
import aegis.protocol.Protocol;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TCP fanout vs real multicast vs unicast-UDP fallback (Bài 8.1.1: "tại mỗi thời điểm trên đường
 * truyền multicast chỉ có một gói tin được gởi đi" vs nhân bản tại nguồn).
 *
 * Measured per mode, same JVM/host (System.nanoTime is comparable):
 * - publisherPackets/Bytes: copies the source places on its link (send/write calls),
 * - receivedCopies/Bytes: total deliveries across N subscribers (network/kernel replication),
 * - latency: send→receive per message per subscriber (avg/p50/p95/max),
 * - fanoutWallMillis: time for the publisher to push all M messages.
 *
 * TCP mode carries FULL content (EBSP ALERT_PUSH frames) N times; multicast/unicast modes carry
 * the small advisory ALERT_HINT once (multicast) or N times (fallback) — the architectural point:
 * with multicast, full content is fetched over TLS only by subscribers that need it.
 */
public final class FanoutBenchmark {

  public record Config(int subscribers, int messages, int payloadBytes, MulticastConfig mcast) {
    public Config {
      if (subscribers < 1 || subscribers > 256) throw new IllegalArgumentException("subscribers 1..256");
      if (messages < 1 || messages > 100_000) throw new IllegalArgumentException("messages 1..100000");
      if (payloadBytes < 64 || payloadBytes > 65_000) throw new IllegalArgumentException("payloadBytes 64..65000");
      if (mcast == null) throw new IllegalArgumentException("null mcast config");
    }
    public static Config defaults() { return new Config(8, 100, 512, MulticastConfig.defaults()); }
  }

  public record ModeResult(String mode, long publisherPackets, long publisherBytes,
                           long receivedCopies, long receivedBytes,
                           long latencyAvgNanos, long latencyP50Nanos, long latencyP95Nanos,
                           long latencyMaxNanos, long fanoutWallMillis, String note) {}

  public record Result(String generatedAt, String environment, Config config,
                       MulticastSupport.ProbeResult probe, List<ModeResult> modes) {}

  public static final String MODE_TCP = "TCP_FANOUT";
  public static final String MODE_MCAST = "MULTICAST";
  public static final String MODE_UNICAST = "UNICAST_UDP_FALLBACK";

  private final Config cfg;
  private final ObjectMapper mapper = new ObjectMapper();
  private final FrameCodec codec = new FrameCodecImpl(mapper);
  private final LabDatagramCodec hintCodec = new LabDatagramCodec();

  public FanoutBenchmark(Config cfg) { this.cfg = cfg; }

  public Result run() throws Exception {
    MulticastSupport.ProbeResult probe = MulticastSupport.probe(cfg.mcast());
    List<ModeResult> modes = new ArrayList<>();
    modes.add(tcpFanout());
    if (probe.available()) modes.add(multicast());
    modes.add(unicastFallback(probe));
    return new Result(Instant.now().toString(), environment(), cfg, probe, List.copyOf(modes));
  }

  // ---- TCP fanout: N full-content copies over N TCP connections ----
  private ModeResult tcpFanout() throws Exception {
    int n = cfg.subscribers(), m = cfg.messages();
    long[] sendNanos = new long[m + 1];
    long[][] recvNanos = new long[n][m + 1];
    long[] recvBytes = new long[n];
    int[] recvCount = new int[n];
    long publisherBytes = 0;
    long wall;
    try (ServerSocket ss = new ServerSocket()) {
      ss.setReuseAddress(true);
      ss.bind(new InetSocketAddress("127.0.0.1", 0), n + 1);
      int port = ss.getLocalPort();
      CountDownLatch connected = new CountDownLatch(n);
      CountDownLatch done = new CountDownLatch(n);
      for (int i = 0; i < n; i++) {
        final int idx = i;
        Thread t = new Thread(() -> {
          try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(30_000);
            connected.countDown();
            CountingInputStream in = new CountingInputStream(s.getInputStream());
            for (int k = 0; k < m; k++) {
              Envelope e = codec.readFrame(in);
              int seq = e.payload().path("seq").asInt(-1);
              if (seq >= 1 && seq <= m && recvNanos[idx][seq] == 0) {
                recvNanos[idx][seq] = System.nanoTime();
                recvCount[idx]++;
              }
            }
            recvBytes[idx] = in.count;
          } catch (Exception ignore) {
            // delivery shortfall shows up in receivedCopies
          } finally {
            done.countDown();
          }
        }, "bench-tcp-sub-" + i);
        t.setDaemon(true);
        t.start();
      }
      List<Socket> accepted = new ArrayList<>(n);
      for (int i = 0; i < n; i++) accepted.add(ss.accept());
      if (!connected.await(10, TimeUnit.SECONDS)) throw new IOException("TCP subscribers did not connect");
      List<OutputStream> outs = new ArrayList<>(n);
      for (Socket s : accepted) { s.setTcpNoDelay(true); outs.add(s.getOutputStream()); }

      String baseBody = "";
      int baseLen = codec.encode(pushEnvelope(1, baseBody)).length;
      String padding = "x".repeat(Math.max(0, cfg.payloadBytes() - baseLen));
      long t0 = System.nanoTime();
      for (int seq = 1; seq <= m; seq++) {
        byte[] frame = codec.encode(pushEnvelope(seq, padding));
        sendNanos[seq] = System.nanoTime();
        for (OutputStream out : outs) { out.write(frame); out.flush(); }
        publisherBytes += (long) frame.length * n;
      }
      wall = (System.nanoTime() - t0) / 1_000_000;
      boolean finished = done.await(30, TimeUnit.SECONDS);
      for (Socket s : accepted) { try { s.close(); } catch (IOException ignore) { } }
      if (!finished) throw new IOException("TCP fanout did not complete within 30s");
    }
    long copies = 0, bytes = 0;
    for (int i = 0; i < n; i++) { copies += recvCount[i]; bytes += recvBytes[i]; }
    long[] stats = latencyStats(sendNanos, recvNanos, n, m);
    return new ModeResult(MODE_TCP, (long) n * m, publisherBytes, copies, bytes,
        stats[0], stats[1], stats[2], stats[3], wall,
        "Publisher writes N full-content EBSP ALERT_PUSH copies over N TCP connections "
            + "(reliable, connection-oriented; TLS excluded from this transport-level bench).");
  }

  private Envelope pushEnvelope(int seq, String body) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("alertId", "bench-alert");
    payload.put("revision", seq);
    payload.put("seq", seq);
    payload.put("body", body);
    return new Envelope(Protocol.VERSION, MessageType.ALERT_PUSH.name(), UUID.randomUUID().toString(),
        null, Instant.now().toString(), payload);
  }

  // ---- Real multicast: ONE hint datagram per message on the source link ----
  private ModeResult multicast() throws Exception {
    int n = cfg.subscribers(), m = cfg.messages();
    long[] sendNanos = new long[m + 1];
    long[][] recvNanos = new long[n][m + 1];
    List<MulticastHintSubscriber> subs = new ArrayList<>(n);
    MulticastHintPublisher pub = new MulticastHintPublisher(cfg.mcast());
    try {
      for (int i = 0; i < n; i++) {
        final int idx = i;
        subs.add(new MulticastHintSubscriber(cfg.mcast(), hint -> {
          int seq = (int) hint.revision();
          if (seq >= 1 && seq <= m && recvNanos[idx][seq] == 0) recvNanos[idx][seq] = System.nanoTime();
        }));
      }
      Thread.sleep(150); // let IGMP joins settle before the first datagram
      long t0 = System.nanoTime();
      for (int seq = 1; seq <= m; seq++) {
        sendNanos[seq] = System.nanoTime();
        pub.publish(new AlertHint("bench-alert", seq, LabDefaults.SERVER_ID));
      }
      long wall = (System.nanoTime() - t0) / 1_000_000;
      awaitHints(() -> { for (MulticastHintSubscriber s : subs) if (s.hintsReceived() < m) return false; return true; }, 15_000);
      long copies = 0, bytes = 0;
      for (MulticastHintSubscriber s : subs) { copies += s.hintsReceived(); bytes += s.bytesReceived(); }
      long[] stats = latencyStats(sendNanos, recvNanos, n, m);
      return new ModeResult(MODE_MCAST, pub.packetsSent(), pub.bytesSent(), copies, bytes,
          stats[0], stats[1], stats[2], stats[3], wall,
          "Real MulticastSocket " + cfg.mcast().group() + ":" + cfg.mcast().port()
              + " ttl=" + cfg.mcast().ttl() + " iface=" + (cfg.mcast().interfaceName() == null ? "default" : cfg.mcast().interfaceName())
              + ": 1 copy on the source link; replication in network/kernel (Bài 8, Hình 8.1). "
              + "Advisory ALERT_HINT only — authoritative content still fetched over TLS per subscriber (not counted here).");
    } finally {
      for (MulticastHintSubscriber s : subs) s.close();
      pub.close();
    }
  }

  // ---- Unicast UDP fallback: N hint copies, connectionless ----
  private ModeResult unicastFallback(MulticastSupport.ProbeResult probe) throws Exception {
    int n = cfg.subscribers(), m = cfg.messages();
    long[] sendNanos = new long[m + 1];
    long[][] recvNanos = new long[n][m + 1];
    List<UnicastHintSubscriber> subs = new ArrayList<>(n);
    UnicastHintPublisher pub = new UnicastHintPublisher();
    try {
      for (int i = 0; i < n; i++) {
        final int idx = i;
        UnicastHintSubscriber s = new UnicastHintSubscriber(hint -> {
          int seq = (int) hint.revision();
          if (seq >= 1 && seq <= m && recvNanos[idx][seq] == 0) recvNanos[idx][seq] = System.nanoTime();
        });
        subs.add(s);
        pub.addTarget(s.unicastEndpoint());
      }
      long t0 = System.nanoTime();
      for (int seq = 1; seq <= m; seq++) {
        sendNanos[seq] = System.nanoTime();
        pub.publish(new AlertHint("bench-alert", seq, LabDefaults.SERVER_ID));
      }
      long wall = (System.nanoTime() - t0) / 1_000_000;
      awaitHints(() -> { for (UnicastHintSubscriber s : subs) if (s.hintsReceived() < m) return false; return true; }, 15_000);
      long copies = 0, bytes = 0;
      for (UnicastHintSubscriber s : subs) { copies += s.hintsReceived(); bytes += s.bytesReceived(); }
      long[] stats = latencyStats(sendNanos, recvNanos, n, m);
      return new ModeResult(MODE_UNICAST, pub.packetsSent(), pub.bytesSent(), copies, bytes,
          stats[0], stats[1], stats[2], stats[3], wall,
          "Unicast UDP fallback — the mode FallbackHintBus uses when the multicast probe fails "
              + "(here also run for comparison; probe said: " + probe.detail() + "). N copies on the "
              + "source link like TCP fanout, but connectionless — no handshake/retransmission overhead.");
    } finally {
      for (UnicastHintSubscriber s : subs) s.close();
      pub.close();
    }
  }

  // ---- helpers ----
  private interface BoolSupplier { boolean get(); }

  private static void awaitHints(BoolSupplier cond, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (!cond.get() && System.currentTimeMillis() < deadline) Thread.sleep(20);
  }

  private static long[] latencyStats(long[] sendNanos, long[][] recvNanos, int n, int m) {
    long[] samples = new long[n * m];
    int count = 0;
    for (int i = 0; i < n; i++)
      for (int seq = 1; seq <= m; seq++)
        if (recvNanos[i][seq] != 0 && sendNanos[seq] != 0)
          samples[count++] = recvNanos[i][seq] - sendNanos[seq];
    if (count == 0) return new long[]{0, 0, 0, 0};
    long[] s = Arrays.copyOf(samples, count);
    Arrays.sort(s);
    long sum = 0;
    for (long v : s) sum += v;
    return new long[]{sum / count, percentile(s, 50), percentile(s, 95), s[count - 1]};
  }

  private static long percentile(long[] sorted, int p) {
    int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
  }

  private static String environment() {
    String host;
    try { host = InetAddress.getLocalHost().getHostName(); } catch (IOException ex) { host = "unknown"; }
    return System.getProperty("os.name") + " " + System.getProperty("os.version")
        + " (" + System.getProperty("os.arch") + "), Java " + System.getProperty("java.version")
        + ", host " + host;
  }

  private static final class CountingInputStream extends FilterInputStream {
    long count;
    CountingInputStream(InputStream in) { super(in); }
    @Override public int read() throws IOException {
      int r = super.read();
      if (r >= 0) count++;
      return r;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      int r = super.read(b, off, len);
      if (r > 0) count += r;
      return r;
    }
  }
}
