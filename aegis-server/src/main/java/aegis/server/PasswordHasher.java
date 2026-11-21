package aegis.server;

/** Password/credential hashing contract (production: Argon2id). */
public interface PasswordHasher {
  /** Returns an encoded hash (Argon2id: {@code $argon2id$v=19$m=...,t=...,p=...$salt$hash}). */
  String hash(String raw);

  /** Constant-time verification of a raw credential against an encoded hash. */
  boolean verify(String raw, String encodedHash);
}
