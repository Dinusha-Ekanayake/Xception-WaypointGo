package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.ModelMetrics.Scored;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Roadmap MF-03, R-ML-13. */
class ModelMetricsTest {
  @Test
  void brierAndLogLossOfAKnownSet() {
    List<Scored> s = List.of(new Scored(0.8, true), new Scored(0.2, false), new Scored(0.6, false));
    assertEquals((0.04 + 0.04 + 0.36) / 3, ModelMetrics.brier(s).orElseThrow(), 1e-12);
    assertEquals(-(Math.log(0.8) + Math.log(0.8) + Math.log(0.4)) / 3, ModelMetrics.logLoss(s).orElseThrow(), 1e-12);
    assertEquals(Optional.empty(), ModelMetrics.brier(List.of()));
  }

  @Test
  void aCalibratedModelFitsSlopeOneInterceptZeroAndAnOverconfidentOneASlopeBelowOne() {
    Random random = new Random(7);
    List<Scored> calibrated = new ArrayList<>();
    List<Scored> overconfident = new ArrayList<>();
    for (int i = 0; i < 20_000; i++) {
      double p = 0.05 + 0.9 * random.nextDouble();
      boolean late = random.nextDouble() < p;
      calibrated.add(new Scored(p, late));
      // The same outcomes, the log-odds doubled: too sure of itself.
      double z = 2 * Math.log(p / (1 - p));
      overconfident.add(new Scored(1 / (1 + Math.exp(-z)), late));
    }
    ModelMetrics.Calibration c = ModelMetrics.calibration(calibrated).orElseThrow();
    assertEquals(1.0, c.slope(), 0.06);
    assertEquals(0.0, c.intercept(), 0.06);
    assertEquals(0.5, ModelMetrics.calibration(overconfident).orElseThrow().slope(), 0.05);
  }

  @Test
  void tooFewOrOneClassIsNotFitted() {
    List<Scored> few = new ArrayList<>();
    for (int i = 0; i < 29; i++) {
      few.add(new Scored(0.5, i % 2 == 0));
    }
    assertTrue(ModelMetrics.calibration(few).isEmpty());
    List<Scored> allOnTime = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      allOnTime.add(new Scored(0.3, false));
    }
    assertTrue(ModelMetrics.calibration(allOnTime).isEmpty());
  }

  @Test
  void meanAbsoluteErrorOfServiceMinutes() {
    assertEquals(3.0, ModelMetrics.meanAbsoluteError(List.of(new double[] {10, 12}, new double[] {20, 16})).orElseThrow());
  }

  @Test
  void wapeIsTheErrorOverWhatWasOrderedAndAnEmptyWeekIsNotPerfect() {
    assertEquals(0.1, ModelMetrics.wape(List.of(new double[] {110, 100}, new double[] {90, 100})).orElseThrow(), 1e-12);
    assertTrue(ModelMetrics.wape(List.of(new double[] {5, 0})).isEmpty());
  }

  @Test
  void psiIsNearZeroForTheSameDistributionAndLargeForAShiftedOne() {
    Random random = new Random(3);
    List<Double> reference = new ArrayList<>();
    List<Double> same = new ArrayList<>();
    List<Double> shifted = new ArrayList<>();
    for (int i = 0; i < 5000; i++) {
      reference.add(random.nextDouble() * 0.5);
      same.add(random.nextDouble() * 0.5);
      shifted.add(0.2 + random.nextDouble() * 0.5);
    }
    assertTrue(ModelMetrics.psi(reference, same).orElseThrow() < 0.02);
    assertTrue(ModelMetrics.psi(reference, shifted).orElseThrow() > 0.25);
    assertTrue(ModelMetrics.psi(reference.subList(0, 99), same).isEmpty());
  }
}
