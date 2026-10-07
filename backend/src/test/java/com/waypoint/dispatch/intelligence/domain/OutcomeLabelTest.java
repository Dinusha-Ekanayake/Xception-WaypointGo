package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waypoint.dispatch.intelligence.domain.OutcomeLabel.Label;
import com.waypoint.dispatch.intelligence.domain.OutcomeLabel.Source;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Roadmap MF-02, R-ML-12: a missing or untrusted arrival is no label, never "not late". */
class OutcomeLabelTest {
  private static final Optional<Instant> ARRIVED = Optional.of(Instant.parse("2026-10-07T01:00:00Z"));

  @Test
  void anArrivalRecordedOnTimeIsLabelledLateOrNot() {
    assertEquals(new Label(Optional.of(true), Source.DEVICE), OutcomeLabel.of(ARRIVED, false, Optional.of(12)));
    assertEquals(new Label(Optional.of(false), Source.DEVICE), OutcomeLabel.of(ARRIVED, false, Optional.of(0)));
    assertEquals(new Label(Optional.of(false), Source.DEVICE), OutcomeLabel.of(ARRIVED, false, Optional.empty()));
  }

  @Test
  void noArrivalOrAReplayedTimeIsNoLabel() {
    assertEquals(new Label(Optional.empty(), Source.NONE), OutcomeLabel.of(Optional.empty(), false, Optional.empty()));
    assertEquals(new Label(Optional.empty(), Source.NONE), OutcomeLabel.of(ARRIVED, true, Optional.of(270)));
  }

  @Test
  void coverageIsTheShareLabelledAndAnEmptyDayIsNotAlarming() {
    assertEquals(0.75, OutcomeLabel.coverage(3, 4));
    assertEquals(1.0, OutcomeLabel.coverage(0, 0));
  }

  private static final Instant CLOSE = Instant.parse("2026-10-07T02:00:00Z");
  private static final Optional<double[]> OUTLET = Optional.of(new double[] {7.291000, 80.633000});

  private static OutcomeLabel.Fix fix(String at, double lat, double lon, boolean low) {
    return new OutcomeLabel.Fix(Instant.parse(at), lat, lon, low);
  }

  @Test
  void withNoTrustworthyTapTheFirstGoodFixAtTheOutletIsTheArrival() {
    var fixes = java.util.List.of(
        fix("2026-10-07T01:40:00Z", 7.300000, 80.640000, false),  // 1.2 km away
        fix("2026-10-07T01:55:00Z", 7.291200, 80.633100, true),   // close, but low quality
        fix("2026-10-07T02:05:00Z", 7.291300, 80.633200, false)); // about 40 m away, after the close
    assertEquals(new Label(Optional.of(true), Source.GEOFENCE),
        OutcomeLabel.of(Optional.empty(), false, Optional.empty(), OUTLET, fixes, CLOSE));
    assertEquals(new Label(Optional.of(true), Source.GEOFENCE),
        OutcomeLabel.of(ARRIVED, true, Optional.of(270), OUTLET, fixes, CLOSE), "a replayed tap gives way to the GPS");
  }

  @Test
  void aTrustworthyTapWinsAndAnInexactOutletOrNoFixLeavesNoLabel() {
    var fixes = java.util.List.of(fix("2026-10-07T02:05:00Z", 7.291300, 80.633200, false));
    assertEquals(Source.DEVICE, OutcomeLabel.of(ARRIVED, false, Optional.of(0), OUTLET, fixes, CLOSE).source());
    assertEquals(new Label(Optional.empty(), Source.NONE),
        OutcomeLabel.of(Optional.empty(), false, Optional.empty(), Optional.empty(), fixes, CLOSE));
    assertEquals(new Label(Optional.empty(), Source.NONE),
        OutcomeLabel.of(Optional.empty(), false, Optional.empty(), OUTLET, java.util.List.of(), CLOSE));
  }
}
