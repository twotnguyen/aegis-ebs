package aegis.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClientConfigTest {

  @Test void defaultsTargetLocalTlsServer() {
    ClientConfig d = ClientConfig.defaults();
    assertEquals("localhost", d.host());
    assertEquals(9443, d.port());
    assertEquals(4, d.retryMaxAttempts());
    assertEquals(3000, d.retryBaseDelayMs());
    assertEquals(10_000, d.heartbeatTimeoutMs());
    assertFalse(d.soundEnabled());
  }

  @Test void parsesAllFlags() {
    ClientConfig c = ClientConfig.fromArgs(new String[]{
        "--host", "127.0.0.1", "--port", "19443", "--device-id", "DEV-B01-F05-01",
        "--user-id", "USER-9", "--device-secret", "s3cret",
        "--truststore", "/tmp/ts.p12", "--truststore-password", "pw",
        "--inbox", "/tmp/inbox.db", "--sound", "--retry-max", "7",
        "--retry-base-ms", "50", "--heartbeat-timeout-ms", "500"});
    assertEquals("127.0.0.1", c.host());
    assertEquals(19443, c.port());
    assertEquals("DEV-B01-F05-01", c.deviceId());
    assertEquals("USER-9", c.userId());
    assertEquals("s3cret", c.deviceSecret());
    assertEquals(Path.of("/tmp/ts.p12"), c.truststore());
    assertEquals("pw", c.truststorePassword());
    assertEquals(Path.of("/tmp/inbox.db"), c.inboxDb());
    assertTrue(c.soundEnabled());
    assertEquals(7, c.retryMaxAttempts());
    assertEquals(50, c.retryBaseDelayMs());
    assertEquals(500, c.heartbeatTimeoutMs());
    assertFalse(ClientConfig.fromArgs(new String[]{"--no-sound"}).soundEnabled());
  }

  @Test void unknownFlagAndMissingValueRejected() {
    assertThrows(IllegalArgumentException.class, () -> ClientConfig.fromArgs(new String[]{"--bogus"}));
    assertThrows(IllegalArgumentException.class, () -> ClientConfig.fromArgs(new String[]{"--host"}));
    assertThrows(IllegalArgumentException.class,
        () -> ClientConfig.fromArgs(new String[]{"--port", "99999"}));
    assertThrows(IllegalArgumentException.class,
        () -> ClientConfig.fromArgs(new String[]{"--retry-max", "0"}));
  }
}
