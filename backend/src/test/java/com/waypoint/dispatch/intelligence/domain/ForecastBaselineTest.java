package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Roadmap T2-03, R-ML-21: the 8-week mean every forecast has to beat. */
class ForecastBaselineTest {
  @Test
  void theBaselineIsTheMeanOfTheWeeksThatHadOrders() {
    assertEquals(105.0, ForecastBaseline.of(List.of(100.0, 110.0, 90.0, 120.0, 100.0, 110.0, 100.0, 110.0)).orElseThrow(), 1e-9);
    // Two weeks before the series began do not drag the mean down.
    assertEquals(100.0, ForecastBaseline.of(List.of(0.0, 0.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0)).orElseThrow(), 1e-9);
  }

  @Test
  void fewerThanFourWeeksWithOrdersGiveNoBaseline() {
    assertTrue(ForecastBaseline.of(List.of(0.0, 0.0, 0.0, 0.0, 0.0, 90.0, 100.0, 110.0)).isEmpty());
    assertTrue(ForecastBaseline.of(List.of()).isEmpty());
  }
}
