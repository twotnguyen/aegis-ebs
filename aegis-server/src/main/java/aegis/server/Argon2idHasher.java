package aegis.server;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;

/**
 * Argon2id hasher (de.mkammerer argon2-jvm, native libs bundled for
 * linux/win/darwin incl. aarch64). Parameters are injectable so tests run fast
 * while production defaults stay conservative.
 */
public final class Argon2idHasher implements PasswordHasher {

  private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
  private final int iterations;
  private final int memoryKib;
  private final int parallelism;

  public Argon2idHasher(int iterations, int memoryKib, int parallelism) {
    if (iterations < 1 || memoryKib < 8 || parallelism < 1) {
      throw new IllegalArgumentException("invalid argon2 parameters");
    }
    this.iterations = iterations;
    this.memoryKib = memoryKib;
    this.parallelism = parallelism;
  }

  @Override
  public String hash(String raw) {
    char[] chars = raw.toCharArray();
    try {
      return argon2.hash(iterations, memoryKib, parallelism, chars);
    } finally {
      argon2.wipeArray(chars);
    }
  }

  @Override
  public boolean verify(String raw, String encodedHash) {
    if (raw == null || encodedHash == null || !encodedHash.startsWith("$argon2id$")) {
      return false;
    }
    char[] chars = raw.toCharArray();
    try {
      return argon2.verify(encodedHash, chars);
    } catch (RuntimeException e) {
      return false; // malformed hash encoding must not crash auth
    } finally {
      argon2.wipeArray(chars);
    }
  }
}
