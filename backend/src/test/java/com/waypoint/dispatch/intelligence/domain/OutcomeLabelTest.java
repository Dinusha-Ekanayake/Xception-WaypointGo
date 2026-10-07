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
}
