package com.waypoint.dispatch.platform.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of every object before it reaches the store (roadmap M-01): AES-256
 * in GCM, a fresh 12-byte nonce each time, and the object's key as associated
 * data, so ciphertext copied under another name, or changed by one bit, does not
 * open. The stored form is {@code WPE1}, the nonce, then the ciphertext and tag.
 */
public final class ObjectCipher {
  static final byte[] MAGIC = "WPE1".getBytes(StandardCharsets.US_ASCII);
  static final int NONCE = 12;
  static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public ObjectCipher(String base64Key) {
    byte[] raw = Base64.getDecoder().decode(base64Key.strip());
    if (raw.length != 32) throw new IllegalArgumentException("The object store key must be 32 bytes");
    this.key = new SecretKeySpec(raw, "AES");
  }

  public byte[] seal(String objectKey, byte[] plain) {
    try {
      byte[] nonce = new byte[NONCE];
      random.nextBytes(nonce);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      c.updateAAD(objectKey.getBytes(StandardCharsets.UTF_8));
      byte[] sealed = c.doFinal(plain);
      return ByteBuffer.allocate(MAGIC.length + NONCE + sealed.length).put(MAGIC).put(nonce).put(sealed).array();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM is unavailable", e);
    }
  }

  /** @throws GeneralSecurityException when the object is not ours, was changed, or sits under another key */
  public byte[] open(String objectKey, byte[] stored) throws GeneralSecurityException {
    if (stored.length < MAGIC.length + NONCE + TAG_BITS / 8 || !Arrays.equals(Arrays.copyOf(stored, MAGIC.length), MAGIC)) {
      throw new GeneralSecurityException("Not a sealed object");
    }
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, MAGIC.length, NONCE));
    c.updateAAD(objectKey.getBytes(StandardCharsets.UTF_8));
    return c.doFinal(stored, MAGIC.length + NONCE, stored.length - MAGIC.length - NONCE);
  }
}
