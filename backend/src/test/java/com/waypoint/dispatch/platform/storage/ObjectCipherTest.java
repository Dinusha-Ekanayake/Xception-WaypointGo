package com.waypoint.dispatch.platform.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** M-01: the provider holds only ciphertext, and a moved or changed object does not open. */
class ObjectCipherTest {
  final ObjectCipher cipher = new ObjectCipher(Base64.getEncoder().encodeToString(new byte[32]));
  final byte[] photo = "a photo of a damaged crate".getBytes(StandardCharsets.UTF_8);

  @Test
  void whatIsSealedOpensUnderItsOwnKeyOnly() throws Exception {
    byte[] sealed = cipher.seal("issues/2026/10/09/a", photo);
    assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains("damaged crate"), "ciphertext, not the photo");
    assertArrayEquals(photo, cipher.open("issues/2026/10/09/a", sealed));
    assertThrows(GeneralSecurityException.class, () -> cipher.open("issues/2026/10/09/b", sealed), "copied under another name");
  }

  @Test
  void aChangedObjectOrAnotherKeyIsRefused() {
    byte[] sealed = cipher.seal("voice/n", photo);
    byte[] changed = Arrays.copyOf(sealed, sealed.length);
    changed[changed.length - 1] ^= 1;
    assertThrows(GeneralSecurityException.class, () -> cipher.open("voice/n", changed));
    byte[] other = new byte[32];
    other[0] = 1;
    ObjectCipher another = new ObjectCipher(Base64.getEncoder().encodeToString(other));
    assertThrows(GeneralSecurityException.class, () -> another.open("voice/n", sealed));
    assertThrows(GeneralSecurityException.class, () -> cipher.open("voice/n", photo), "plain bytes are not a sealed object");
  }

  @Test
  void theSameFileSealsDifferentlyEachTime() {
    assertFalse(Arrays.equals(cipher.seal("k", photo), cipher.seal("k", photo)), "a fresh nonce each time");
  }
}
