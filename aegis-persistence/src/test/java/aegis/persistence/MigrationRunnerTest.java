package aegis.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRunnerTest {
  @TempDir Path dir;

  static final Set<String> EXPECTED_TABLES = Set.of(
      // 001 base schema (12)
      "buildings", "floors", "users", "role_grants", "devices", "alerts",
      "alert_targets", "deliveries", "delivery_attempts", "device_acks",
      "audit_events", "alert_approvals",
      // 002 showcase expansion (14)
      "zones", "zone_devices", "incidents", "incident_events", "incident_alerts",
      "alert_revisions", "alert_templates", "alert_template_locales",
      "alert_operations", "operation_receipts", "network_fault_runs",
      "network_fault_events", "metrics_samples", "demo_scenarios");

  @Test
  void freshDatabaseGetsExactly26TablesInOrder() throws Exception {
    Db db = new Db(dir.resolve("fresh.db"));
    List<String> applied = new MigrationRunner(db).migrate();
    assertEquals(List.of("db/001_base_schema.sql", "db/002_showcase_expansion.sql"), applied);
    Set<String> tables = tableNames(db);
    assertEquals(26, tables.size(), "expected exactly 26 tables, got: " + tables);
    assertEquals(EXPECTED_TABLES, tables);
  }

  @Test
  void secondMigrateIsNoOp() throws Exception {
    Db db = TestDb.fresh(dir);
    List<String> applied = new MigrationRunner(db).migrate();
    assertTrue(applied.isEmpty(), "already-applied migrations must not re-run");
    assertEquals(26, tableNames(db).size());
  }

  @Test
  void foreignKeyCheckCleanWithSampleDataInAll26Tables() throws Exception {
    Db db = TestDb.fresh(dir);
    Fixtures.seedAll(db);
    try (Connection c = db.open(); Statement s = c.createStatement()) {
      try (ResultSet rs = s.executeQuery("PRAGMA foreign_key_check")) {
        assertFalse(rs.next(), "foreign_key_check must return no rows");
      }
      for (String table : EXPECTED_TABLES) {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
          assertTrue(rs.next());
          assertTrue(rs.getInt(1) >= 1, "table not seeded: " + table);
        }
      }
    }
  }

  static Set<String> tableNames(Db db) throws Exception {
    Set<String> names = new HashSet<>();
    try (Connection c = db.open();
         Statement s = c.createStatement();
         ResultSet rs = s.executeQuery(
             "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
      while (rs.next()) names.add(rs.getString(1));
    }
    return names;
  }
}
