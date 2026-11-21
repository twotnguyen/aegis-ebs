package aegis.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Argon2idHasherTest {

  private final Argon2idHasher hasher = new Argon2idHasher(1, 4096, 1);

  @Test
  void hashIsArgon2idEncodedAndVerifies() {
    String hash = hasher.hash("correct horse battery staple");
    assertTrue(hash.startsWith("$argon2id$"), "expected argon2id encoding, got: " + hash);
    assertTrue(hasher.verify("correct horse battery staple", hash));
  }

  @Test
  void wrongPasswordFails() {
    String hash = hasher.hash("s3cret");
    assertFalse(hasher.verify("wrong", hash));
    assertFalse(hasher.verify("", hash));
  }

  @Test
  void saltsMakeHashesUnique() {
    assertNotEquals(hasher.hash("same"), hasher.hash("same"));
  }

  @Test
  void malformedOrForeignHashesFailClosed() {
    assertFalse(hasher.verify("x", null));
    assertFalse(hasher.verify("x", "garbage"));
    assertFalse(hasher.verify("x", "$argon2i$v=19$m=4096,t=1,p=1$c29tZXNhbHQ$hash"));
    assertFalse(hasher.verify(null, "$argon2id$whatever"));
  }

  @Test
  void unicodeCredentialsRoundTrip() {
    String hash = hasher.hash("mật-khẩu-🔥");
    assertTrue(hasher.verify("mật-khẩu-🔥", hash));
    assertFalse(hasher.verify("mat-khau", hash));
  }
}
