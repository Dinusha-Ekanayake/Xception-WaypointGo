package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.LateNoticePolicy.Audience;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Roadmap T1-02, R-ML-22: who hears that a stop will likely be late. */
class LateNoticePolicyTest {
  @Test
  void onlyALikelyLateStopIsToldAboutAndOnlyOnce() {
    assertEquals(Optional.empty(), LateNoticePolicy.decide(0.49, false, 0, true));
    assertEquals(Optional.of(Audience.STORE), LateNoticePolicy.decide(0.5, false, 0, true));
    assertEquals(Optional.empty(), LateNoticePolicy.decide(0.9, true, 0, true), "told once");
  }

  @Test
  void whileLearningOrPastTheWeeklyCapTheDispatcherIsToldInstead() {
    assertEquals(Optional.of(Audience.DISPATCHER), LateNoticePolicy.decide(0.8, false, 0, false));
    assertEquals(Optional.of(Audience.STORE), LateNoticePolicy.decide(0.8, false, 1, true));
    assertEquals(Optional.of(Audience.DISPATCHER), LateNoticePolicy.decide(0.8, false, 2, true));
  }

  @Test
  void theStoreGateOpensOnlyOnAPrecisionLowerBoundOfEightyPercent() {
    // 95 of 100 flagged stops really were late: the lower bound is about 0.888.
    assertEquals(0.8882, LateNoticePolicy.wilsonLower(95, 100), 1e-3);
    assertTrue(LateNoticePolicy.storesOpen(95, 100));
    // 85% looks good but the bound is under 0.8 on 100 stops.
    assertFalse(LateNoticePolicy.storesOpen(85, 100));
    // Perfect on too few stops is not enough.
    assertFalse(LateNoticePolicy.storesOpen(29, 29));
    assertEquals(0, LateNoticePolicy.wilsonLower(0, 0));
  }
}
