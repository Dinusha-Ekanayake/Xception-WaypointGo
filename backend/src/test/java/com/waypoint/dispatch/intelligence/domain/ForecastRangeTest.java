package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.ForecastRange.Pair;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Roadmap MF-06, R-ML-20: a weekly forecast's range from pooled finished weeks. */
class ForecastRangeTest {
  @Test
  void theRangeIsTheMiddleEightyPercentOfPooledMissesOnALogScale() {
    // Ordered over forecast evenly from 0.8 to 1.25 on a log scale.
    List<Pair> pairs = new ArrayList<>();
    for (int i = 0; i <= 100; i++) {
      double log = Math.log(0.8) + (Math.log(1.25) - Math.log(0.8)) * i / 100;
      pairs.add(new Pair(100, 100 * Math.exp(log)));
    }
    var f = ForecastRange.factors(pairs).orElseThrow();
    assertEquals(101, f.weeks());
    double[] r = ForecastRange.apply(400, f);
    assertEquals(400 * Math.exp(Math.log(0.8) + 0.1 * (Math.log(1.25) - Math.log(0.8))), r[0], 1e-6);
    assertEquals(400 * Math.exp(Math.log(0.8) + 0.9 * (Math.log(1.25) - Math.log(0.8))), r[1], 1e-6);
    assertTrue(r[0] < 400 && 400 < r[1]);
  }

  @Test
  void tooFewFinishedWeeksGiveNoRangeAndEmptyWeeksDoNotCount() {
    List<Pair> pairs = new ArrayList<>();
    for (int i = 0; i < 29; i++) {
      pairs.add(new Pair(100, 110));
    }
    pairs.add(new Pair(0, 50));
    pairs.add(new Pair(80, 0));
    assertTrue(ForecastRange.factors(pairs).isEmpty(), "29 usable weeks are not enough");
  }

  @Test
  void aForecastAlwaysLiesInsideItsRange() {
    List<Pair> pairs = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      pairs.add(new Pair(100, 120 + i));
    }
    double[] r = ForecastRange.apply(50, ForecastRange.factors(pairs).orElseThrow());
    assertEquals(50, r[0], 1e-9, "every week was under-forecast, so the range starts at the forecast");
    assertTrue(r[1] > 60);
  }
}
