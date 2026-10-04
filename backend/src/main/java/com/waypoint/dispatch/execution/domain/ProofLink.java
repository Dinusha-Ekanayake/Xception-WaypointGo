package com.waypoint.dispatch.execution.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A short-lived, signed link to one proof artifact.
 *
 * <p>The signature covers the attachment and the expiry, so a link cannot be
 * stretched to another artifact or a later time. Whoever holds a link can read
 * that artifact until it expires and nothing else, which is why links are minted
 * only for an actor who was allowed to see the delivery.
 */
public final class ProofLink {
  private final byte[] key;

  public ProofLink(byte[] key) {
    if (key == null || key.length < 16) {
      throw new IllegalArgumentException("The proof link key must be at least 16 bytes");
    }
    this.key = key.clone();
  }

  public String sign(UUID attachmentId, Instant expiresAt) {
    return HexFormat.of().formatHex(mac(attachmentId, expiresAt.getEpochSecond()));
  }

  /** @param now the caller's clock reading; an expired link is refused however well signed */
  public boolean verify(UUID attachmentId, long expiresAtEpochSecond, String signature, Instant now) {
    if (signature == null || now.getEpochSecond() > expiresAtEpochSecond) {
      return false;
    }
    byte[] given;
    try {
      given = HexFormat.of().parseHex(signature);
    } catch (IllegalArgumentException e) {
      return false;
    }
    // Constant time, so the comparison does not leak how much of a guess was right.
    return MessageDigest.isEqual(mac(attachmentId, expiresAtEpochSecond), given);
  }

  private byte[] mac(UUID attachmentId, long expiresAtEpochSecond) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal((attachmentId + "." + expiresAtEpochSecond).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}
