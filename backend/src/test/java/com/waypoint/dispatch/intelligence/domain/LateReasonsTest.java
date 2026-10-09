package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.LateReasons.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Roadmap MF-08, R-ML-15: plain reasons, strongest first, at most three. */
class LateReasonsTest {
  @Test
  void aComfortableEarlyStopAtAReliableStoreHasNoReason() {
    assertTrue(LateReasons.of(90, 0, 0, 10).isEmpty());
  }

  @Test
  void eachFactIsAReasonWithItsNumber() {
    assertEquals(List.of(new Reason("TIGHT_WINDOW", 14, 0)), LateReasons.of(14, 1, 0, 10));
    assertEquals(List.of(new Reason("LATE_IN_ROUTE", 6, 0)), LateReasons.of(120, 5, 0, 10));
    assertEquals(List.of(new Reason("OUTLET_HISTORY", 3, 6)), LateReasons.of(120, 0, 3, 6));
  }

  @Test
  void theStrongestComesFirst() {
    // 5 min of slack outweighs the 6th stop and a store late on half its visits.
    List<Reason> r = LateReasons.of(5, 5, 3, 6);
    assertEquals(List.of("TIGHT_WINDOW", "LATE_IN_ROUTE", "OUTLET_HISTORY"), r.stream().map(Reason::code).toList());
  }

  @Test
  void tooFewVisitsOrAGoodRecordIsNoHistoryReason() {
    assertTrue(LateReasons.of(120, 0, 2, 2).isEmpty(), "two visits are too few to judge");
    assertTrue(LateReasons.of(120, 0, 1, 10).isEmpty(), "late once in ten");
  }

  @Test
  void aPlannedArrivalAfterTheCloseIsZeroSlack() {
    assertEquals(new Reason("TIGHT_WINDOW", 0, 0), LateReasons.of(-12, 0, 0, 0).get(0));
  }
}
