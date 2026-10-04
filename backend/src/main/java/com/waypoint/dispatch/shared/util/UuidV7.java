package com.waypoint.dispatch.shared.util;

import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * UUID version 7 (RFC 9562): 48 bits of Unix milliseconds, then random bits.
 *
 * <p>Event ids and operational surrogate keys use v7 so they sort by creation
 * time, which keeps B-tree inserts at the right-hand edge of the index. Time and
 * randomness are parameters, as everywhere in the domain: a caller passes the
 * injected clock's instant and a generator, and a test passes fixed ones.
 */
public final class UuidV7 {
  private UuidV7() {}

  public static UUID generate(Instant now, RandomGenerator random) {
    long millis = now.toEpochMilli();
    if (millis < 0 || millis >= (1L << 48)) {
      throw new IllegalArgumentException("UUIDv7 time must fit in 48 bits: " + now);
    }
    long randA = random.nextLong() & 0x0FFFL;
    long most = (millis << 16) | (0x7L << 12) | randA;
    long randB = random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
    long least = 0x8000_0000_0000_0000L | randB;
    return new UUID(most, least);
  }
}
