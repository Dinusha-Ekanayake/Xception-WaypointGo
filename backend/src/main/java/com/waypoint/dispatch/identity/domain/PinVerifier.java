package com.waypoint.dispatch.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What a shared dock device keeps so a loader can switch to themselves with a
 * PIN while offline (decision 2026-10-01): a PBKDF2-HMAC-SHA256 hash, never the PIN.
 *
 * <p>Format {@code pbkdf2-sha256$<iterations>$<salt b64>$<hash b64>}, which the
 * browser recomputes with WebCrypto. A four-digit PIN has ten thousand values,
 * so anyone holding a verifier can find the PIN whatever the iteration count.
 * It is downloaded only to a supervisor-signed-in loader device, expires with
 * the crew list and is wiped at sign-out; that is the accepted risk (R-IAM-27).
 */
public final class PinVerifier {
  public static final int ITERATIONS = 100_000;
  public static final int SALT_BYTES = 16;
  private static final int HASH_BYTES = 32;

  private PinVerifier() {}

  public static String create(String pin, byte[] salt, int iterations) {
    PinPolicy.requireWellFormed(pin);
    Base64.Encoder b64 = Base64.getEncoder();
    return "pbkdf2-sha256$" + iterations + "$" + b64.encodeToString(salt) + "$"
        + b64.encodeToString(derive(pin, salt, iterations));
  }

  public static boolean matches(String pin, String verifier) {
    if (pin == null || verifier == null) {
      return false;
    }
    String[] parts = verifier.split("\\$");
    if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
      return false;
    }
    try {
      Base64.Decoder b64 = Base64.getDecoder();
      int iterations = Integer.parseInt(parts[1]);
      if (iterations < 1) {
        return false;
      }
      return MessageDigest.isEqual(b64.decode(parts[3]), derive(pin, b64.decode(parts[2]), iterations));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** PBKDF2 over HMAC-SHA256 with one 32-byte block, as WebCrypto's deriveBits(256) computes it. */
  private static byte[] derive(String pin, byte[] salt, int iterations) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pin.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(salt);
      byte[] u = mac.doFinal(new byte[] {0, 0, 0, 1});
      byte[] out = u.clone();
      for (int i = 1; i < iterations; i++) {
        u = mac.doFinal(u);
        for (int j = 0; j < HASH_BYTES; j++) {
          out[j] ^= u[j];
        }
      }
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is always available", e);
    }
  }
}
