package aegis.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;

/** Shared JDBC helpers: UUID text ids and UTC ISO-8601 timestamps. */
final class Sql {
  /**
   * Fixed-width UTC ISO-8601 with exactly 9 fractional digits (nanoseconds):
   * lexicographic TEXT order (ORDER BY created_at/occurred_at) then matches
   * chronological order even when precisions are mixed, and round-trips are
   * lossless. Reads use {@link Instant#parse}, which also accepts legacy
   * variable-width ISO_INSTANT rows written before this normalization.
   */
  static final DateTimeFormatter TS = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

  private Sql() {}

  static String uuid() {
    return UUID.randomUUID().toString();
  }

  static String ts(Instant instant) {
    return instant == null ? null : TS.format(instant);
  }

  static void setTs(PreparedStatement ps, int index, Instant instant) throws SQLException {
    ps.setString(index, ts(instant));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    String raw = rs.getString(column);
    return raw == null ? null : Instant.parse(raw);
  }

  static int bool(boolean value) {
    return value ? 1 : 0;
  }
}
