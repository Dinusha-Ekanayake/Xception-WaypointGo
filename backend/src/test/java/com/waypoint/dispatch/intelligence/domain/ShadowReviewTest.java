package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.contract.ModelViews.ShadowRole;
import com.waypoint.dispatch.intelligence.domain.ShadowReview.Pair;
import com.waypoint.dispatch.intelligence.domain.ShadowReview.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Issue #12, R-ML-38 and R-ML-40: the shadow judged on the same stops, and when the system rolls back. */
class ShadowReviewTest {
  /** Stops whose truth the shadow knows {@code shadowSharpness} of, and the incumbent {@code incumbentSharpness}. */
  private static List<Pair> pairs(int n, double shadowSharpness, double incumbentSharpness, long seed) {
    Random random = new Random(seed);
    List<Pair> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      double truth = random.nextDouble();
      boolean late = random.nextDouble() < truth;
      out.add(new Pair(0.5 + shadowSharpness * (truth - 0.5), 0.5 + incumbentSharpness * (truth - 0.5), late));
    }
    return out;
  }

  @Test
  void aSharperShadowIsBetterOnBothScoresAndADullerOneWorse() {
    var better = ShadowReview.judge(pairs(400, 0.9, 0.3, 1));
    assertEquals(Verdict.BETTER, better.verdict());
    assertEquals(400, better.stops());
    assertTrue(better.shadowBrier().get() < better.incumbentBrier().get());
    assertEquals(Verdict.WORSE, ShadowReview.judge(pairs(400, 0.3, 0.9, 2)).verdict());
  }

  @Test
  void underTwoHundredStopsOrOneClassOnlyNothingIsDecided() {
    assertEquals(Verdict.NOT_ENOUGH, ShadowReview.judge(pairs(ShadowReview.MIN_STOPS - 1, 0.9, 0.1, 3)).verdict());
    List<Pair> allOnTime = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      allOnTime.add(new Pair(0.1, 0.4, false));
    }
    assertEquals(Verdict.NOT_ENOUGH, ShadowReview.judge(allOnTime).verdict());
    assertEquals(Verdict.NOT_ENOUGH, ShadowReview.judge(List.of()).verdict());
    assertTrue(ShadowReview.judge(List.of()).shadowBrier().isEmpty());
  }

  @Test
  void betterOnOneScoreAndWorseOnTheOtherIsMixed() {
    // An overconfident shadow: right more often, so a lower Brier, but its rare confident misses cost log loss.
    List<Pair> mixed = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      boolean late = i % 3 == 0;
      double shadow = i % 10 == 0 ? (late ? 0.0001 : 0.9999) : (late ? 0.95 : 0.05);
      mixed.add(new Pair(shadow, late ? 0.6 : 0.4, late));
    }
    var r = ShadowReview.judge(mixed);
    assertTrue(r.shadowBrier().get() < r.incumbentBrier().get());
    assertTrue(r.shadowLogLoss().get() > r.incumbentLogLoss().get());
    assertEquals(Verdict.MIXED, r.verdict());
  }

  @Test
  void theWindowStartsAtTheShadowsFirstDayWhenThatIsLater() {
    LocalDate today = LocalDate.of(2026, 11, 20);
    assertEquals(LocalDate.of(2026, 11, 6), ShadowReview.windowFrom(today, LocalDate.of(2026, 10, 1)));
    assertEquals(LocalDate.of(2026, 11, 15), ShadowReview.windowFrom(today, LocalDate.of(2026, 11, 15)));
  }

  @Test
  void onlyAReplacedModelThatDidBetterSoonAfterThePromotionIsPutBack() {
    Instant promoted = Instant.parse("2026-11-01T04:00:00Z");
    Instant soon = promoted.plus(Duration.ofDays(10));
    assertTrue(ShadowReview.rollBack(ShadowRole.REPLACED, Verdict.BETTER, Optional.of(promoted), soon));
    assertFalse(ShadowReview.rollBack(ShadowRole.CANDIDATE, Verdict.BETTER, Optional.of(promoted), soon),
        "R-ML-39: a candidate is promoted by a person");
    for (Verdict v : List.of(Verdict.WORSE, Verdict.MIXED, Verdict.NOT_ENOUGH)) {
      assertFalse(ShadowReview.rollBack(ShadowRole.REPLACED, v, Optional.of(promoted), soon), v.name());
    }
    assertFalse(ShadowReview.rollBack(ShadowRole.REPLACED, Verdict.BETTER, Optional.of(promoted),
        promoted.plus(ShadowReview.ROLLBACK_WINDOW).plusSeconds(1)), "late: a person's to retire");
    assertFalse(ShadowReview.rollBack(ShadowRole.REPLACED, Verdict.BETTER, Optional.empty(), soon));
  }
}
