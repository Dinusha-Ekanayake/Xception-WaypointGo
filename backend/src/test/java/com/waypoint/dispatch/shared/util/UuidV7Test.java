package com.waypoint.dispatch.shared.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

  @Test
  void carriesVersionSevenAndTheRfcVariant() {
    UUID id = UuidV7.generate(Instant.parse("2026-09-30T10:15:30Z"), new SplittableRandom(1));

    assertEquals(7, id.version());
    assertEquals(2, id.variant());
  }

  @Test
  void encodesTheMillisecondsInTheHighBits() {
    Instant now = Instant.parse("2026-09-30T10:15:30.123Z");

    UUID id = UuidV7.generate(now, new SplittableRandom(1));

    assertEquals(now.toEpochMilli(), id.getMostSignificantBits() >>> 16);
  }

  @Test
  void sortsByTimeAcrossMilliseconds() {
    SplittableRandom random = new SplittableRandom(42);
    Instant earlier = Instant.parse("2026-09-30T10:15:30.000Z");

    UUID first = UuidV7.generate(earlier, random);
    UUID second = UuidV7.generate(earlier.plusMillis(1), random);

    assertTrue(
        first.toString().compareTo(second.toString()) < 0,
        "a later id must sort after an earlier one as text, which is how the index sees it");
  }

  @Test
  void refusesAnInstantBeforeTheEpoch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> UuidV7.generate(Instant.parse("1969-12-31T23:59:59Z"), new SplittableRandom(1)));
  }
}
