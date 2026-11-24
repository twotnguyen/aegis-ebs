package aegis.networklab;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Writes the fanout comparison report as JSON (machine-readable) + Markdown (human-readable). */
public final class BenchmarkReport {

  public record ReportPaths(Path json, Path markdown) {}

  private BenchmarkReport() {}

  public static ReportPaths write(FanoutBenchmark.Result result, Path dir) throws IOException {
    Files.createDirectories(dir);
    Path json = dir.resolve("fanout-report.json");
    Path md = dir.resolve("fanout-report.md");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(json.toFile(), result);
    Files.writeString(md, toMarkdown(result), StandardCharsets.UTF_8);
    return new ReportPaths(json, md);
  }

  public static String toMarkdown(FanoutBenchmark.Result r) {
    StringBuilder sb = new StringBuilder();
    sb.append("# AEGIS network lab — TCP fanout vs multicast vs unicast fallback\n\n");
    sb.append("- Generated: ").append(r.generatedAt()).append('\n');
    sb.append("- Environment: ").append(r.environment()).append('\n');
    sb.append("- Config: subscribers=").append(r.config().subscribers())
        .append(", messages=").append(r.config().messages())
        .append(", TCP payload≈").append(r.config().payloadBytes()).append(" bytes")
        .append(", group=").append(r.config().mcast().group())
        .append(":").append(r.config().mcast().port())
        .append(", ttl=").append(r.config().mcast().ttl())
        .append(", iface=").append(r.config().mcast().interfaceName() == null ? "default" : r.config().mcast().interfaceName())
        .append('\n');
    sb.append("- Multicast probe: ").append(r.probe().available() ? "AVAILABLE" : "UNAVAILABLE")
        .append(" — ").append(r.probe().detail()).append("\n\n");
    sb.append("| Mode | Pub packets (source-link copies) | Pub bytes | Recv copies (all subs) | Recv bytes | Latency avg µs | p50 µs | p95 µs | max µs | Fanout wall ms |\n");
    sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
    for (FanoutBenchmark.ModeResult m : r.modes()) {
      sb.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %.1f | %.1f | %.1f | %.1f | %d |%n",
          m.mode(), m.publisherPackets(), m.publisherBytes(), m.receivedCopies(), m.receivedBytes(),
          m.latencyAvgNanos() / 1000.0, m.latencyP50Nanos() / 1000.0,
          m.latencyP95Nanos() / 1000.0, m.latencyMaxNanos() / 1000.0, m.fanoutWallMillis()));
    }
    sb.append('\n');
    FanoutBenchmark.ModeResult tcp = find(r.modes(), FanoutBenchmark.MODE_TCP);
    FanoutBenchmark.ModeResult mc = find(r.modes(), FanoutBenchmark.MODE_MCAST);
    FanoutBenchmark.ModeResult uc = find(r.modes(), FanoutBenchmark.MODE_UNICAST);
    if (tcp != null && mc != null && tcp.publisherPackets() > 0) {
      double pktSaved = 100.0 * (tcp.publisherPackets() - mc.publisherPackets()) / tcp.publisherPackets();
      double byteSaved = tcp.publisherBytes() == 0 ? 0
          : 100.0 * (tcp.publisherBytes() - mc.publisherBytes()) / tcp.publisherBytes();
      sb.append(String.format(Locale.ROOT,
          "**Multicast vs TCP fanout:** source-link application writes −%.1f%% (%d → %d), source-link application bytes −%.1f%%. ",
          pktSaved, tcp.publisherPackets(), mc.publisherPackets(), byteSaved));
      sb.append("These are publisher-level writes/datagrams on loopback, not packet captures; "
          + "hints carry only advisory metadata and exclude the TLS content fetch.\n");
      if (mc.latencyAvgNanos() > 0)
        sb.append(String.format(Locale.ROOT, "Avg hint latency %.1f µs vs TCP full-content %.1f µs (%.2f×).%n",
            mc.latencyAvgNanos() / 1000.0, tcp.latencyAvgNanos() / 1000.0,
            (double) tcp.latencyAvgNanos() / mc.latencyAvgNanos()));
      else sb.append('\n');
    }
    if (tcp != null && uc != null)
      sb.append(String.format(Locale.ROOT,
          "**Unicast UDP fallback vs TCP fanout:** same %d source-link copies, avg latency %.1f µs vs %.1f µs.%n",
          uc.publisherPackets(), uc.latencyAvgNanos() / 1000.0, tcp.latencyAvgNanos() / 1000.0));
    sb.append("\n## Notes per mode\n\n");
    for (FanoutBenchmark.ModeResult m : r.modes())
      sb.append("- **").append(m.mode()).append("**: ").append(m.note()).append('\n');
    sb.append("""

        ## Caveats (đọc trước khi trích dẫn số liệu)

        - Đo trên MỘT host (loopback): phần "nhân bản" của multicast do kernel thực hiện cho các
          socket đã joinGroup; trên mạng thật, việc nhân bản xảy ra tại switch/router theo cây
          multicast (Bài 8, Hình 8.1) — số gói trên link nguồn vẫn là 1.
        - Multicast/unicast ở đây chở ALERT_HINT advisory (datagram nhỏ); TCP fanout chở toàn bộ
          nội dung (EBSP ALERT_PUSH). Kiến trúc thật: hint chỉ kích hoạt fetch nội dung qua TLS
          đã xác thực — chi phí fetch đó KHÔNG tính trong benchmark transport này.
        - Latency = nanoTime điểm gửi → điểm nhận trong cùng JVM (đồng hồ so sánh được), chưa gồm
          hàng đợi ứng dụng phía subscriber.
        - TCP bench không bật TLS để cô lập chi phí fanout transport; kênh production là TLS
          (them handshake + record overhead mỗi connection).
        - UDP không đảm bảo giao hàng (Bài 7.1.3): receivedCopies < N×M phản ánh mất gói thực tế.
        """);
    return sb.toString();
  }

  private static FanoutBenchmark.ModeResult find(List<FanoutBenchmark.ModeResult> modes, String name) {
    for (FanoutBenchmark.ModeResult m : modes) if (m.mode().equals(name)) return m;
    return null;
  }
}
