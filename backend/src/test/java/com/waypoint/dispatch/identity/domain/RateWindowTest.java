package com.waypoint.dispatch.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** R-IAM-33: the counting window and when a refused caller may try again. */
class RateWindowTest {
  @Test
  void requestsInTheSameMinuteShareAWindowAndTheNextMinuteStartsFresh() {
    RateWindow a = RateWindow.containing(Instant.parse("2026-10-03T10:15:01Z"), RateWindow.MINUTE);
    RateWindow b = RateWindow.containing(Instant.parse("2026-10-03T10:15:59.999Z"), RateWindow.MINUTE);
    RateWindow c = RateWindow.containing(Instant.parse("2026-10-03T10:16:00Z"), RateWindow.MINUTE);
    assertEquals(Instant.parse("2026-10-03T10:15:00Z"), a.start());
    assertEquals(a, b);
    assertEquals(Instant.parse("2026-10-03T10:16:00Z"), c.start());
  }

  @Test
  void retryAfterIsTheTimeToTheNextWindowAndNeverZero() {
    RateWindow w = RateWindow.containing(Instant.parse("2026-10-03T10:15:00Z"), RateWindow.MINUTE);
    assertEquals(60, w.secondsUntilNext(Instant.parse("2026-10-03T10:15:00Z")));
    assertEquals(30, w.secondsUntilNext(Instant.parse("2026-10-03T10:15:30Z")));
    assertEquals(1, w.secondsUntilNext(Instant.parse("2026-10-03T10:15:59.900Z")), "rounded up, never 0");
  }

  @Test
  void theLimitItselfIsAllowedAndOnlyTheFirstRefusalIsAudited() {
    assertFalse(RateWindow.over(3, 3));
    assertTrue(RateWindow.over(4, 3));
    assertTrue(RateWindow.firstRefusal(4, 3));
    assertFalse(RateWindow.firstRefusal(5, 3), "a flood is counted, not audited row by row");
  }
}
