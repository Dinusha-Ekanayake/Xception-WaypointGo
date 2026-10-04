package com.waypoint.dispatch.platform.scheduling;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** A cron read on the business clock follows the demo clock's jumps (issue #231). */
class BusinessCronTickTest {
  private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
  /** 2026-10-05 15:59:50 in Colombo. */
  private static final Instant BEFORE_CUTOFF = Instant.parse("2026-10-05T10:29:50Z");

  @Test
  void firesOnceWhenTheClockPassesTheCronTime() {
    BusinessCronTick tick = new BusinessCronTick("0 0 16 * * *", COLOMBO);
    assertFalse(tick.due(BEFORE_CUTOFF), "the first reading only starts the count");
    assertFalse(tick.due(BEFORE_CUTOFF.plusSeconds(5)));
    assertTrue(tick.due(BEFORE_CUTOFF.plusSeconds(15)));
    assertFalse(tick.due(BEFORE_CUTOFF.plusSeconds(30)), "fires once, not on every tick after");
  }

  @Test
  void aJumpForwardPastSeveralTimesFiresOnce() {
    BusinessCronTick tick = new BusinessCronTick("0 0 16 * * *", COLOMBO);
    tick.due(BEFORE_CUTOFF);
    assertTrue(tick.due(BEFORE_CUTOFF.plus(Duration.ofDays(3).plusHours(1))));
    assertFalse(tick.due(BEFORE_CUTOFF.plus(Duration.ofDays(3).plusHours(1)).plusSeconds(15)));
  }

  @Test
  void aJumpBackFiresNothingAndCountsFromTheNewReading() {
    BusinessCronTick tick = new BusinessCronTick("0 0 16 * * *", COLOMBO);
    tick.due(BEFORE_CUTOFF.plus(Duration.ofHours(2)));
    assertFalse(tick.due(BEFORE_CUTOFF), "moved back before 16:00");
    assertTrue(tick.due(BEFORE_CUTOFF.plusSeconds(15)), "and 16:00 comes round again");
  }
}
