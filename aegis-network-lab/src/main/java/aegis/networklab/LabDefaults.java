package aegis.networklab;

/** Lab defaults from the task spec; every value is overridable via configs/CLI. */
public final class LabDefaults {
  public static final int DISCOVERY_PORT = 9999;
  public static final String MULTICAST_GROUP = "239.10.10.10"; // administratively-scoped (Bài 8.1.2)
  public static final int MULTICAST_PORT = 9998;
  public static final int MULTICAST_TTL = 1; // link-local by default (Bài 7.1.1 TTL)
  public static final int TLS_PORT = 9443;
  public static final String SERVER_ID = "aegis-lab-server";
  public static final String SERVER_HOST = "127.0.0.1";
  private LabDefaults() {}
}
