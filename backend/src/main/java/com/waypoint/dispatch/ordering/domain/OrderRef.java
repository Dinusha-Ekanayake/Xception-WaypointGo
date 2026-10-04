package com.waypoint.dispatch.ordering.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * The business identifier of an order, shown to people and sent to the warehouse.
 *
 * <p>Derived from the command rather than minted, so the same command always
 * yields the same reference. That is what makes the warehouse call safe to
 * repeat: {@code POST /orders} is not idempotent (R-STK-11), so a retry after a
 * rolled-back or retried transaction must find the first attempt by reference
 * instead of creating a second order. The actor is part of the input because
 * command ids are unique per actor, not globally.
 *
 * <p>{@code WPO-} and 12 Crockford base32 characters (60 bits). The prefix keeps
 * it apart from the dataset's {@code ORD0091466} identifiers.
 */
public final class OrderRef {
  private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  static final String PREFIX = "WPO-";
  static final int LENGTH = 12;

  private OrderRef() {}

  public static String derive(UUID actorId, UUID commandId) {
    byte[] digest = sha256(actorId + ":" + commandId);
    StringBuilder ref = new StringBuilder(PREFIX);
    long bits = 0;
    for (int i = 0; i < 8; i++) {
      bits = (bits << 8) | (digest[i] & 0xFF);
    }
    for (int i = 0; i < LENGTH; i++) {
      int shift = 64 - 5 * (i + 1);
      ref.append(CROCKFORD[(int) ((bits >>> shift) & 0x1F)]);
    }
    return ref.toString();
  }

  private static byte[] sha256(String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the Java platform", e);
    }
  }
}
