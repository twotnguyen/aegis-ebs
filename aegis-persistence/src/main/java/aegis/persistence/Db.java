package aegis.persistence;

import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SQLite connection factory. Every connection is configured with
 * {@code PRAGMA foreign_keys=ON}, {@code busy_timeout=5000} and
 * {@code journal_mode=WAL} (in that order) before it is handed out.
 *
 * <p>Connections are short-lived: callers use try-with-resources. WAL allows
 * concurrent readers alongside one writer; busy_timeout rides out lock waits.
 * Initialization is serialized per database file inside this JVM (concurrent
 * first-openers of a fresh file otherwise race on header creation and WAL
 * conversion), and the WAL switch additionally retries SQLITE_BUSY within the
 * busy_timeout budget for cross-process contention.
 */
public final class Db {
  private static final int BUSY_TIMEOUT_MILLIS = 5000;
  private static final int SQLITE_BUSY_PRIMARY_CODE = 5;
  /** One lock per distinct database file opened by this JVM (tiny, long-lived). */
  private static final ConcurrentHashMap<String, Object> OPEN_LOCKS = new ConcurrentHashMap<>();

  private final String url;

  public Db(Path dbFile) {
    Objects.requireNonNull(dbFile, "dbFile");
    this.url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
  }

  public String url() {
    return url;
  }

  /** Opens a new connection with the required pragmas applied. */
  public Connection open() throws SQLException {
    // Serialize initialization per file: racing first-openers of a fresh file
    // can hit SQLITE_BUSY and even native IOERR_SHORT_READ/IOERR_FSTAT while
    // the header and WAL sidecar files are being created. Once the file is
    // WAL, later opens are cheap no-ops under the same lock.
    Object lock = OPEN_LOCKS.computeIfAbsent(url, k -> new Object());
    synchronized (lock) {
      Connection c = DriverManager.getConnection(url);
      try (Statement s = c.createStatement()) {
        s.execute("PRAGMA foreign_keys=ON");
        // Arm the busy handler BEFORE the WAL switch so ordinary lock waits
        // (BEGIN IMMEDIATE, writes) ride out contention instead of failing.
        s.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLIS);
        switchToWal(s);
      } catch (SQLException | RuntimeException e) {
        try {
          c.close();
        } catch (SQLException close) {
          e.addSuppressed(close);
        }
        throw e;
      }
      return c;
    }
  }

  /**
   * Converts the file to WAL, retrying SQLITE_BUSY within the busy_timeout
   * budget. The journal_mode switch needs a brief exclusive lock and can fail
   * with SQLITE_BUSY WITHOUT invoking the busy handler when several
   * connections race to convert a fresh file. Once any connection completes
   * the switch the file stays WAL and later opens are no-ops, so a short
   * backoff retry closes the first-open race (also across processes).
   */
  private void switchToWal(Statement s) throws SQLException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUSY_TIMEOUT_MILLIS);
    long backoffMillis = 5;
    while (true) {
      try (ResultSet rs = s.executeQuery("PRAGMA journal_mode=WAL")) {
        if (rs.next() && "wal".equalsIgnoreCase(rs.getString(1))) {
          return;
        }
        throw new SQLException("SQLite refused WAL journal_mode for " + url);
      } catch (SQLException e) {
        boolean busy = e instanceof SQLiteException se
            && (se.getResultCode().code & 0xFF) == SQLITE_BUSY_PRIMARY_CODE;
        if (!busy || System.nanoTime() - deadline >= 0) {
          throw e;
        }
        try {
          Thread.sleep(backoffMillis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          e.addSuppressed(ie);
          throw e;
        }
        backoffMillis = Math.min(backoffMillis * 2, 200);
      }
    }
  }
}
