package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.Backtest.Outcome;
import com.waypoint.dispatch.intelligence.domain.Backtest.Result;
import com.waypoint.dispatch.intelligence.domain.Backtest.Verdict;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Roadmap MF-04, R-ML-16: each model judged on what it predicted, against the built-in estimate. */
class BacktestTest {
  private static List<Outcome> outcomes(int n, double sharpness, long seed) {
    Random random = new Random(seed);
    List<Outcome> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      double truth = random.nextDouble();
      boolean late = random.nextDouble() < truth;
      // A sharp model says close to the truth; a dull one says 0.5 more often.
      double said = 0.5 + sharpness * (truth - 0.5);
      out.add(new Outcome(LocalDate.of(2026, 9, 7).plusDays(i % 56), Optional.of(said), Optional.of(late)));
    }
    return out;
  }

  @Test
  void aSharperModelThanTheEstimateOnEnoughStopsIsBetterAndADullerOneWorse() {
    List<Result> r = Backtest.run(Map.of(
        "deterministic", outcomes(600, 0.4, 1),
        "sharp@1", outcomes(600, 0.95, 1),
        "dull@1", outcomes(600, 0.1, 1)));
    Map<String, Verdict> v = new java.util.HashMap<>();
    r.forEach(x -> v.put(x.modelLabel(), x.verdict()));
    assertEquals(Verdict.BASELINE, v.get("deterministic"));
    assertEquals(Verdict.BETTER, v.get("sharp@1"));
    assertEquals(Verdict.WORSE, v.get("dull@1"));
    Result sharp = r.stream().filter(x -> x.modelLabel().equals("sharp@1")).findFirst().orElseThrow();
    assertEquals(8, sharp.weeks().size(), "56 days are eight ISO weeks");
    assertEquals(1.0, sharp.coverage());
  }

  @Test
  void tooFewLabelsOrNoBaselineIsNotEnoughToJudge() {
    assertEquals(Verdict.NOT_ENOUGH, Backtest.run(Map.of("deterministic", outcomes(600, 0.4, 2), "m@1", outcomes(150, 0.9, 2)))
        .stream().filter(x -> x.modelLabel().equals("m@1")).findFirst().orElseThrow().verdict());
    assertEquals(Verdict.NOT_ENOUGH, Backtest.run(Map.of("m@1", outcomes(600, 0.9, 3))).get(0).verdict());
  }

  @Test
  void stopsWithNoLabelLowerTheCoverageAndAreNotScored() {
    List<Outcome> some = new ArrayList<>(outcomes(300, 0.6, 4));
    for (int i = 0; i < 100; i++) {
      some.add(new Outcome(LocalDate.of(2026, 9, 8), Optional.of(0.3), Optional.empty()));
    }
    Result r = Backtest.run(Map.of("deterministic", some)).get(0);
    assertEquals(400, r.stops());
    assertEquals(300, r.labelled());
    assertEquals(0.75, r.coverage());
    assertTrue(r.brier().isPresent());
  }
}
