package aegis.networklab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark correctness: copy/packet counters must match the transport semantics
 * (TCP fanout N×M source copies; multicast M source copies but N×M deliveries;
 * unicast fallback N×M), latencies recorded, report files written and parseable.
 */
class FanoutBenchmarkTest {

  @TempDir
  Path reportDir;

  @Test
  void countersMatchTransportSemanticsAndReportIsWritten() throws Exception {
    int n = 4, m = 30;
    FanoutBenchmark.Config cfg = new FanoutBenchmark.Config(n, m, 256, MulticastConfig.defaults());
    FanoutBenchmark.Result result = new FanoutBenchmark(cfg).run();

    FanoutBenchmark.ModeResult tcp = find(result, FanoutBenchmark.MODE_TCP);
    assertNotNull(tcp);
    assertEquals((long) n * m, tcp.publisherPackets(), "TCP fanout writes N copies per message");
    assertEquals((long) n * m, tcp.receivedCopies(), "TCP is reliable: all copies delivered on loopback");
    assertTrue(tcp.publisherBytes() >= (long) n * m * 200, "full-content frames, not tiny hints");
    assertTrue(tcp.latencyAvgNanos() > 0);
    assertTrue(tcp.latencyMaxNanos() < 30_000_000_000L);

    FanoutBenchmark.ModeResult uc = find(result, FanoutBenchmark.MODE_UNICAST);
    assertNotNull(uc);
    assertEquals((long) n * m, uc.publisherPackets(), "unicast fallback = N copies per message");
    assertEquals((long) n * m, uc.receivedCopies());
    assertTrue(uc.publisherBytes() < tcp.publisherBytes(), "hints are much smaller than full content");

    FanoutBenchmark.ModeResult mc = find(result, FanoutBenchmark.MODE_MCAST);
    if (result.probe().available()) {
      assertNotNull(mc, "multicast mode must run when the probe verifies real delivery");
      assertEquals(m, mc.publisherPackets(), "multicast puts ONE copy per message on the source link");
      assertEquals((long) n * m, mc.receivedCopies(), "network/kernel replicates to all N subscribers");
      assertTrue(mc.latencyAvgNanos() > 0);
      System.out.println("[FanoutBenchmarkTest] real multicast measured: " + result.probe().detail());
    } else {
      assertNull(mc);
      System.out.println("[FanoutBenchmarkTest] multicast UNSUPPORTED here (" + result.probe().detail()
          + ") — unicast fallback measured instead");
    }

    BenchmarkReport.ReportPaths paths = BenchmarkReport.write(result, reportDir);
    assertTrue(Files.exists(paths.json()));
    assertTrue(Files.exists(paths.markdown()));
    JsonNode root = new ObjectMapper().readTree(paths.json().toFile());
    assertEquals(result.modes().size(), root.get("modes").size());
    assertEquals(n, root.get("config").get("subscribers").asInt());
    assertTrue(root.get("probe").has("available"));
    String md = Files.readString(paths.markdown());
    assertTrue(md.contains("TCP_FANOUT"));
    assertTrue(md.contains("UNICAST_UDP_FALLBACK"));
    assertTrue(md.contains("Caveats"));
    if (mc != null) assertTrue(md.contains("MULTICAST"));
  }

  private static FanoutBenchmark.ModeResult find(FanoutBenchmark.Result r, String mode) {
    return r.modes().stream().filter(x -> x.mode().equals(mode)).findFirst().orElse(null);
  }
}
