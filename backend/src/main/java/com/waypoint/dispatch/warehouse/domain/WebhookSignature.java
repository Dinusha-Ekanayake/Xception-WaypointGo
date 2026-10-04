package com.waypoint.dispatch.warehouse.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies an inbound warehouse webhook (SEC-18, SEC-19).
 *
 * <p>The signature is {@code hex(HMAC-SHA256(secret, timestamp + "." + rawBody))}
 * over the bytes exactly as received, never a re-serialisation. The timestamp is
 * epoch seconds and must fall inside the replay window, so a captured request
 * stops verifying after a few minutes; an exact replay inside the window is
 * stopped by the unique {@code (source_system, source_event_id)}.
 */
public final class WebhookSignature {
  private WebhookSignature() {}

  /** @return empty when valid, otherwise why not, for the quarantine record */
  public static Optional<String> problem(
      byte[] secret,
      String timestampHeader,
      String signatureHeader,
      byte[] rawBody,
      Instant now,
      Duration window) {
    if (secret == null || secret.length == 0) {
      return Optional.of("no webhook secret configured");
    }
    if (timestampHeader == null || signatureHeader == null) {
      return Optional.of("missing signature or timestamp");
    }
    long seconds;
    try {
      seconds = Long.parseLong(timestampHeader.trim());
    } catch (NumberFormatException e) {
      return Optional.of("timestamp is not epoch seconds");
    }
    Instant sentAt = Instant.ofEpochSecond(seconds);
    if (sentAt.isBefore(now.minus(window)) || sentAt.isAfter(now.plus(window))) {
      return Optional.of("timestamp outside the replay window");
    }
    String given = signatureHeader.trim();
    if (given.startsWith("sha256=")) {
      given = given.substring("sha256=".length());
    }
    byte[] expected = sign(secret, timestampHeader.trim(), rawBody);
    byte[] actual;
    try {
      actual = HexFormat.of().parseHex(given.toLowerCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return Optional.of("signature is not hex");
    }
    return MessageDigest.isEqual(expected, actual)
        ? Optional.empty()
        : Optional.of("signature does not match");
  }

  public static byte[] sign(byte[] secret, String timestamp, byte[] rawBody) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
      mac.update((byte) '.');
      return mac.doFinal(rawBody);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }
}
