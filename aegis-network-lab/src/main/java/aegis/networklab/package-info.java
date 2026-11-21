/**
 * AEGIS Network Lab — CMP180 Bài 7 (UDP) + Bài 8 (Multicast) applied to the EBS capstone.
 *
 * <h2>Channels</h2>
 * <ul>
 *   <li><b>UDP discovery (Bài 7)</b>: client broadcasts {@code DISCOVER_EBS {nonce,version}} to
 *       port {@value aegis.networklab.LabDefaults#DISCOVERY_PORT}; server replies
 *       {@code {serverHost,tlsPort,nonce,serverId}} echoing the nonce. The reply is UNTRUSTED:
 *       {@link aegis.networklab.DiscoveryClient#discoverTrusted} only yields a
 *       {@link aegis.networklab.DiscoveryClient.TrustedServer} after a TLS handshake against a
 *       pinned trust store, an EBSP HELLO/SERVER_HELLO nonce echo over that TLS session, and a
 *       certificate-CN == serverId check. A spoofed UDP reply can therefore never inject content
 *       or redirect the client to an unpinned endpoint.</li>
 *   <li><b>Multicast hints (Bài 8)</b>: {@code MulticastSocket} on group
 *       {@value aegis.networklab.LabDefaults#MULTICAST_GROUP} port
 *       {@value aegis.networklab.LabDefaults#MULTICAST_PORT}; TTL and outgoing interface are
 *       configurable ({@link aegis.networklab.MulticastConfig}). Publisher sends advisory
 *       {@code ALERT_HINT {alertId,revision,serverId}} — never trusted commands. Subscribers
 *       {@code joinGroup}, then fetch authoritative content over authenticated TLS
 *       ({@link aegis.networklab.VerifiedAlertSubscriber}); only TLS-verified content reaches the
 *       display sink (docs/PROTOCOL.md: "Multicast/UDP = advisory hint only").</li>
 *   <li><b>Unicast fallback</b>: when multicast is unavailable (e.g. macOS lo0 has no multicast
 *       route — send succeeds but nothing is delivered), {@link aegis.networklab.MulticastSupport}
 *       detects it with a real send/receive probe and {@link aegis.networklab.FallbackHintBus}
 *       switches to per-subscriber UDP unicast.</li>
 *   <li><b>Benchmark</b>: {@link aegis.networklab.FanoutBenchmark} compares TCP fanout vs
 *       multicast vs unicast-UDP fallback: publisher egress packets/bytes (copies on the source
 *       link), received copies, and end-to-end latency; writes JSON + Markdown reports.</li>
 * </ul>
 *
 * <h2>Wire format (lab-local, NOT EBSP frames)</h2>
 * {@code DISCOVER_EBS}/{@code ALERT_HINT} are not in the frozen {@code aegis.protocol.MessageType}
 * enum, so discovery/hint datagrams use a lab-local codec: one strict-UTF-8 JSON object per UDP
 * datagram, ≤ {@value aegis.networklab.LabDatagramCodec#MAX_DATAGRAM_BYTES} bytes (fits Ethernet
 * MTU 1500 without IP fragmentation — Bài 7.1.1), with a {@code type} discriminator. The trusted
 * channel reuses {@code aegis.protocol.FrameCodecImpl} + {@code Envelope} (EBSP/2.0) over TLS.
 *
 * <h2>Running the lab</h2>
 * See {@code src/main/resources/lab-guide.md}. Acceptance: {@code mvn -q -pl aegis-network-lab test}.
 */
package aegis.networklab;
