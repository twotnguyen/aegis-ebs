package aegis.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbTest {
  @TempDir Path dir;

  @Test
  void everyConnectionGetsRequiredPragmas() throws Exception {
    Db db = TestDb.fresh(dir);
    for (int i = 0; i < 2; i++) {
      try (Connection c = db.open(); Statement s = c.createStatement()) {
        try (ResultSet rs = s.executeQuery("PRAGMA foreign_keys")) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1), "foreign_keys must be ON per connection");
        }
        try (ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
          assertTrue(rs.next());
          assertEquals("wal", rs.getString(1), "journal_mode must be WAL");
        }
        try (ResultSet rs = s.executeQuery("PRAGMA busy_timeout")) {
          assertTrue(rs.next());
          assertEquals(5000, rs.getInt(1), "busy_timeout must be 5000ms");
        }
      }
    }
  }

  @Test
  void foreignKeyViolationsAreRejected() throws Exception {
    Db db = TestDb.fresh(dir);
    try (Connection c = db.open();
         PreparedStatement ps = c.prepareStatement(
             "INSERT INTO floors(id,building_id,floor_number) VALUES(?,?,?)")) {
      ps.setString(1, Sql.uuid());
      ps.setString(2, Sql.uuid()); // no such building
      ps.setInt(3, 1);
      assertThrows(SQLException.class, ps::executeUpdate);
    }
  }

  @Test
  void walAllowsSecondReaderWhileWriteTransactionOpen() throws Exception {
    Db db = TestDb.fresh(dir);
    try (Connection writer = db.open();
         Statement ws = writer.createStatement();
         Connection reader = db.open();
         Statement rs = reader.createStatement()) {
      ws.execute("BEGIN IMMEDIATE");
      ws.executeUpdate("INSERT INTO buildings(id,code,name) VALUES('b-wal','W','WAL')");
      // Uncommitted row invisible to the other connection...
      try (ResultSet r = rs.executeQuery("SELECT count(*) FROM buildings")) {
        assertTrue(r.next());
        assertEquals(0, r.getInt(1));
      }
      ws.execute("COMMIT");
      // ...visible after commit.
      try (ResultSet r = rs.executeQuery("SELECT count(*) FROM buildings")) {
        assertTrue(r.next());
        assertEquals(1, r.getInt(1));
      }
    }
  }
}
