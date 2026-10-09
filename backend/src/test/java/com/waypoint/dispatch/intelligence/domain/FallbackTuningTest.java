package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.FallbackTuning.Labelled;
import com.waypoint.dispatch.intelligence.domain.FallbackTuning.LateTuning;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** R-ML-14: the fallback learns from Waypoint's own outcomes, and stays the rule until it can. */
class FallbackTuningTest {
  @Test
  void tooFewLabelsLeaveTheDocumentedRuleUnchanged() {
    List<Labelled> few = new ArrayList<>();
    for (int i = 0; i < 199; i++) {
      few.add(new Labelled("OUT001", 0.3, i % 3 == 0));
    }
    assertEquals(LateTuning.IDENTITY, FallbackTuning.fit(few));
    assertEquals(
        DeterministicEstimator.lateProbability(LocalTime.of(7, 50), LocalTime.of(8, 0), 1),
        DeterministicEstimator.lateProbability(LocalTime.of(7, 50), LocalTime.of(8, 0), 1, LateTuning.IDENTITY, "OUT001"));
  }

  @Test
  void aRuleThatIsTooSureOfItselfIsCalibratedBack() {
    // The rule's probabilities are twice as extreme in log-odds as the truth.
    Random random = new Random(11);
    List<Labelled> stops = new ArrayList<>();
    for (int i = 0; i < 5000; i++) {
      double truth = 0.05 + 0.9 * random.nextDouble();
      double z = 2 * Math.log(truth / (1 - truth));
      stops.add(new Labelled("OUT" + (i % 50), 1 / (1 + Math.exp(-z)), random.nextDouble() < truth));
    }
    LateTuning t = FallbackTuning.fit(stops);
    assertEquals(0.5, t.slope(), 0.06);
    assertEquals(5000, t.labels());
    // 0.88 said by the rule (log-odds 2) means about 0.73 (log-odds 1).
    assertEquals(0.73, t.apply(1 / (1 + Math.exp(-2)), "OUT-new"), 0.04);
  }

  @Test
  void anOutletThatRunsLateMoreOftenThanPredictedGetsAShrunkOffset() {
    Random random = new Random(5);
    List<Labelled> stops = new ArrayList<>();
    for (int i = 0; i < 2000; i++) {
      String outlet = i % 20 == 0 ? "OUT-SLOW" : "OUT" + (i % 19);
      double p = 0.2;
      double truth = outlet.equals("OUT-SLOW") ? 0.6 : 0.2;
      stops.add(new Labelled(outlet, p, random.nextDouble() < truth));
    }
    LateTuning t = FallbackTuning.fit(stops);
    double slow = t.apply(0.2, "OUT-SLOW");
    double usual = t.apply(0.2, "OUT3");
    assertTrue(slow > 0.4 && slow < 0.65, "shrunk toward the rest, not all the way: " + slow);
    assertTrue(Math.abs(usual - 0.2) < 0.06, "an ordinary outlet stays near 0.2: " + usual);
  }

  @Test
  void serviceMinutesBlendTheOutletsOwnHistoryWithTheAllowanceByHowMuchThereIs() {
    BigDecimal allowance = new BigDecimal("15");
    assertEquals(new BigDecimal("15.00"), DeterministicEstimator.blendedServiceMinutes(List.of(), allowance).minutes());
    // One visit of 25: (1 x 25 + 5 x 15) / 6.
    assertEquals(new BigDecimal("16.67"),
        DeterministicEstimator.blendedServiceMinutes(List.of(new BigDecimal("25")), allowance).minutes());
    List<BigDecimal> long_ = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      long_.add(new BigDecimal("25"));
    }
    // Forty-five visits: (45 x 25 + 5 x 15) / 50 = 24.
    assertEquals(new BigDecimal("24.00"), DeterministicEstimator.blendedServiceMinutes(long_, allowance).minutes());
  }
}
