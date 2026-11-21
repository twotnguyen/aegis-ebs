package aegis.server;

/**
 * One dashboard zone's counters + the NORMATIVE severity rules from
 * aegis-dashboard/docs/status-contract.md (mirrors deriveSeverity in data.js).
 * Counters are monotonic; online is a live gauge (0..devicesTotal).
 */
public record ZoneStatus(int devicesTotal, int online, long received, long acknowledged, long failed) {

  public String severity() {
    return deriveSeverity(devicesTotal, online, received, acknowledged, failed);
  }

  /**
   * <pre>
   * offline  ⇔ online == 0
   * critical ⇔ failed &gt; 0 &amp;&amp; (failed/max(received,1) &gt;= 0.2 || acknowledged/max(received,1) &lt; 0.6)
   * warning  ⇔ online &lt; devicesTotal || failed &gt; 0 || acknowledged &lt; received
   * online   ⇔ otherwise
   * </pre>
   */
  public static String deriveSeverity(int devicesTotal, int online, long received,
                                      long acknowledged, long failed) {
    if (online <= 0) return "offline";
    long r = Math.max(received, 1);
    if (failed > 0 && ((double) failed / r >= 0.2 || (double) acknowledged / r < 0.6)) {
      return "critical";
    }
    if (online < devicesTotal || failed > 0 || acknowledged < received) return "warning";
    return "online";
  }
}
