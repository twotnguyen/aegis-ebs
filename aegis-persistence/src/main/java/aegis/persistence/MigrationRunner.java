package aegis.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the bundled schema migrations in order: 001 base (12 tables) then
 * 002 showcase expansion (14 tables) = 26 tables. Each script runs inside a
 * single BEGIN IMMEDIATE transaction. Applied migrations are detected via
 * sentinel tables so no extra bookkeeping table is added to the schema.
 */
public final class MigrationRunner {
  /** Resource path -> sentinel table proving the script was fully applied. */
  private static final String[][] MIGRATIONS = {
      {"db/001_base_schema.sql", "buildings"},
      {"db/002_showcase_expansion.sql", "demo_scenarios"},
  };

  private final Db db;

  public MigrationRunner(Db db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /**
   * Applies pending migrations.
   *
   * @return resource names applied by this call, in order (empty if up to date)
   */
  public List<String> migrate() throws SQLException {
    List<String> applied = new ArrayList<>();
    try (Connection c = db.open()) {
      for (String[] migration : MIGRATIONS) {
        String resource = migration[0];
        if (isApplied(c, migration[1])) {
          continue;
        }
        String sql = readResource(resource);
        try (Statement s = c.createStatement()) {
          s.execute("BEGIN IMMEDIATE");
          try {
            for (String statement : splitStatements(sql)) {
              s.execute(statement);
            }
            s.execute("COMMIT");
          } catch (SQLException | RuntimeException e) {
            try {
              s.execute("ROLLBACK");
            } catch (SQLException rollback) {
              e.addSuppressed(rollback);
            }
            throw e;
          }
        }
        applied.add(resource);
      }
    }
    return applied;
  }

  private static boolean isApplied(Connection c, String sentinelTable) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
      ps.setString(1, sentinelTable);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static String readResource(String resource) {
    try (InputStream in = MigrationRunner.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Migration resource missing from classpath: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read migration resource: " + resource, e);
    }
  }

  /**
   * Splits a SQL script on ';' while respecting single-quoted strings and
   * '--' line comments (comments are stripped). Sufficient for the bundled
   * schema scripts, which contain no triggers or compound statements.
   */
  static List<String> splitStatements(String script) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inString = false;
    boolean inLineComment = false;
    for (int i = 0; i < script.length(); i++) {
      char ch = script.charAt(i);
      if (inLineComment) {
        if (ch == '\n') {
          inLineComment = false;
          current.append(ch);
        }
        continue;
      }
      if (inString) {
        current.append(ch);
        if (ch == '\'') {
          if (i + 1 < script.length() && script.charAt(i + 1) == '\'') {
            current.append('\''); // escaped quote
            i++;
          } else {
            inString = false;
          }
        }
        continue;
      }
      if (ch == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
        inLineComment = true;
        continue;
      }
      if (ch == '\'') {
        inString = true;
        current.append(ch);
        continue;
      }
      if (ch == ';') {
        addIfNotBlank(statements, current);
        continue;
      }
      current.append(ch);
    }
    addIfNotBlank(statements, current);
    return statements;
  }

  private static void addIfNotBlank(List<String> statements, StringBuilder current) {
    String statement = current.toString().trim();
    current.setLength(0);
    if (!statement.isEmpty()) {
      statements.add(statement);
    }
  }
}
