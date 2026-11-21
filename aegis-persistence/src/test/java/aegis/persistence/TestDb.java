package aegis.persistence;

import java.nio.file.Path;
import java.util.UUID;

/** Test helper: fresh migrated SQLite database in a JUnit @TempDir. */
final class TestDb {
  private TestDb() {}

  static Db fresh(Path dir) throws Exception {
    Path file = dir.resolve("aegis-" + UUID.randomUUID() + ".db");
    Db db = new Db(file);
    new MigrationRunner(db).migrate();
    return db;
  }
}
