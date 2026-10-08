package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.ModelMetrics.Scored;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Roadmap MF-04, T1-02, R-ML-22: the late flag's threshold, and the expected arrival before departure. */
class LateThresholdTest {
  private static List<Scored> scored(int perBucket) {
    // At each probability p in 0.05 steps, a share p of the stops really were late: a calibrated model.
    List<Scored> out = new ArrayList<>();
    for (int b = 1; b <= 19; b++) {
      double p = b * 0.05;
      for (int i = 0; i < perBucket; i++) {
        out.add(new Scored(p, i < Math.round(p * perBucket)));
      }
    }
    return out;
  }

  @Test
  void theLowestThresholdWhosePrecisionLowerBoundReachesSixtyPercentIsChosen() {
    var c = LateThreshold.choose(scored(40)).orElseThrow();
    assertTrue(c.precisionLower() >= 0.6, c.toString());
    // Flagging every stop from 0.35 up is already about 65% precise on a calibrated model.
    assertEquals(0.35, c.threshold(), 1e-9, c.toString());
    // One step lower does not qualify.
    double lower = c.threshold() - 0.05;
    int flagged = 0;
    int late = 0;
    for (Scored s : scored(40)) {
      if (s.probability() >= lower - 1e-9) {
        flagged++;
        late += s.late() ? 1 : 0;
      }
    }
    assertTrue(LateNoticePolicy.wilsonLower(late, flagged) < 0.6);
  }

  @Test
  void tooFewLabelsOrAFlagThatNeverEarnsItFreezesNothing() {
    assertTrue(LateThreshold.choose(scored(10)).isEmpty(), "190 labelled stops are too few");
    List<Scored> useless = new ArrayList<>();
    for (int i = 0; i < 400; i++) {
      useless.add(new Scored(i % 2 == 0 ? 0.95 : 0.05, i % 4 == 0));
    }
    assertTrue(LateThreshold.choose(useless).isEmpty(), "half the flagged stops late is not 60%");
  }

  @Test
  void aStopIsExpectedLaterByHowMuchLongerTheStopsBeforeItTake() {
    List<ExpectedArrival.Stop> trip = List.of(
        new ExpectedArrival.Stop(LocalTime.of(5, 0), 15, 25),
        new ExpectedArrival.Stop(LocalTime.of(5, 40), 15, 10),
        new ExpectedArrival.Stop(LocalTime.of(6, 20), 15, 15));
    // 10 minutes behind after the first; the second gives back 5.
    assertEquals(List.of(LocalTime.of(5, 0), LocalTime.of(5, 50), LocalTime.of(6, 25)), ExpectedArrival.beforeDeparture(trip));
    List<ExpectedArrival.Stop> quick = List.of(
        new ExpectedArrival.Stop(LocalTime.of(5, 0), 20, 10), new ExpectedArrival.Stop(LocalTime.of(5, 40), 20, 20));
    assertEquals(LocalTime.of(5, 40), ExpectedArrival.beforeDeparture(quick).get(1), "never earlier than planned");
    assertEquals(LocalTime.of(7, 45), ExpectedArrival.about(LocalTime.of(7, 43, 10)));
    assertEquals(LocalTime.of(7, 40), ExpectedArrival.about(LocalTime.of(7, 42)));
  }
}
