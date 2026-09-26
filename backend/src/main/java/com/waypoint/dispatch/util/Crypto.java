package com.waypoint.dispatch.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password hashing, token hashing and id generation. Mirrors lib/service.ts. */
public final class Crypto {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private Crypto() {}

  public static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return toHex(hash);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** PBKDF2-HMAC-SHA256, 200k iterations, 32 bytes. Must match the Node build. */
  public static byte[] passwordHash(String password, String saltHex) {
    try {
      byte[] salt = saltHex.getBytes(StandardCharsets.UTF_8);
      PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 200_000, 256);
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      return factory.generateSecret(spec).getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String passwordHashHex(String password, String saltHex) {
    return toHex(passwordHash(password, saltHex));
  }

  public static boolean timingSafeEqual(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }

  public static boolean timingSafeEqualHex(String aHex, String bHex) {
    try {
      return MessageDigest.isEqual(fromHex(aHex), fromHex(bHex));
    } catch (Exception e) {
      return false;
    }
  }

  public static String randomHex(int bytes) {
    byte[] buf = new byte[bytes];
    RANDOM.nextBytes(buf);
    return toHex(buf);
  }

  public static String toHex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = HEX[v >>> 4];
      out[i * 2 + 1] = HEX[v & 0x0F];
    }
    return new String(out);
  }

  public static byte[] fromHex(String hex) {
    int len = hex.length();
    byte[] out = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      out[i / 2] =
          (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
    }
    return out;
  }
}
