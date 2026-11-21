package aegis.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * SQLite connection factory. Every connection is configured with
 * {@code PRAGMA foreign_keys=ON}, {@code journal_mode=WAL} and
 * {@code busy_timeout=5000} before it is handed out.
 *
 * <p>Connections are short-lived: callers use try-with-resources. WAL allows
 * concurrent readers alongside one writer; busy_timeout rides out lock waits.
 */
public final class Db {
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
    Connection c = DriverManager.getConnection(url);
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      try (ResultSet rs = s.executeQuery("PRAGMA journal_mode=WAL")) {
        if (!rs.next() || !"wal".equalsIgnoreCase(rs.getString(1))) {
          throw new SQLException("SQLite refused WAL journal_mode for " + url);
        }
      }
      s.execute("PRAGMA busy_timeout=5000");
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
