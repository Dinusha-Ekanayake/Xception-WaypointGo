package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.ServiceRange.Basis;
import com.waypoint.dispatch.intelligence.domain.ServiceRange.Miss;
import com.waypoint.dispatch.intelligence.domain.ServiceRange.Misses;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Roadmap MF-06, R-ML-20: a range around the expected unloading minutes from earlier misses. */
class ServiceRangeTest {
  private static List<Miss> misses(String outlet, String depot, int n, double from, double to) {
    List<Miss> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(new Miss(outlet, depot, from + (to - from) * i / (n - 1)));
    }
    return out;
  }

  @Test
  void anOutletWithEnoughOwnMissesUsesThemAndTheRangeIsTheMiddleEightyPercent() {
    // Ratios evenly from 0.5 to 1.5: the 10th percentile is 0.6 and the 90th 1.4.
    var r = ServiceRange.of(20, "OUT1", "Kandy", Misses.of(misses("OUT1", "Kandy", 101, 0.5, 1.5))).orElseThrow();
    assertEquals(Basis.OUTLET, r.basis());
    assertEquals(12.0, r.low(), 1e-9);
    assertEquals(28.0, r.high(), 1e-9);
  }

  @Test
  void tooFewAtTheOutletFallsBackToTheDepotThenEveryDepotThenNothing() {
    List<Miss> m = new ArrayList<>(misses("OUT1", "Kandy", 10, 0.9, 1.1));
    m.addAll(misses("OUT2", "Kandy", 25, 0.5, 2.0));
    assertEquals(Basis.DEPOT, ServiceRange.of(15, "OUT1", "Kandy", Misses.of(m)).orElseThrow().basis());
    assertEquals(Basis.ALL, ServiceRange.of(15, "OUT9", "Galle", Misses.of(m)).orElseThrow().basis());
    assertTrue(ServiceRange.of(15, "OUT1", "Kandy", Misses.of(misses("OUT1", "Kandy", 29, 0.9, 1.1))).isEmpty(),
        "29 earlier stops are not enough to say anything");
  }

  @Test
  void theExpectedMinutesAreAlwaysInsideTheirRangeAndBadRatiosAreIgnored() {
    // Every earlier stop took longer than predicted; the range still starts at the expectation.
    List<Miss> m = new ArrayList<>(misses("OUT1", "Kandy", 40, 1.2, 1.6));
    m.add(new Miss("OUT1", "Kandy", 0));
    m.add(new Miss("OUT1", "Kandy", Double.NaN));
    var r = ServiceRange.of(10, "OUT1", "Kandy", Misses.of(m)).orElseThrow();
    assertEquals(10.0, r.low(), 1e-9);
    assertTrue(r.high() > 15 && r.high() <= 16, "high " + r.high());
    assertTrue(ServiceRange.of(0, "OUT1", "Kandy", Misses.of(m)).isEmpty());
  }
}
