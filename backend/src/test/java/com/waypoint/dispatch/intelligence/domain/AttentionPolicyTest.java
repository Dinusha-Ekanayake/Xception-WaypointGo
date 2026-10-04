package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Finding;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Kind;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Progress;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Severity;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.StopFacts;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #268: what on a live trip needs the dispatcher, and when they are reminded. */
class AttentionPolicyTest {
  private static final AttentionThresholds T = AttentionThresholds.DEFAULT;
  private static final Instant NOW = Instant.parse("2026-10-05T04:00:00Z");

  private static Instant in(long minutes) {
    return NOW.plusSeconds(minutes * 60);
  }

  private static StopFacts open(long plannedIn, Optional<Long> expectedIn, long windowClosesIn) {
    return new StopFacts(Progress.OPEN, in(plannedIn), expectedIn.map(AttentionPolicyTest::in), in(windowClosesIn), Optional.empty(), false);
  }

  private static StopFacts done(Progress progress, long completedAgo, boolean proof) {
    return new StopFacts(progress, in(-60), Optional.empty(), in(60), Optional.of(in(-completedAgo)), proof);
  }

  @Test
  void aFailedStopIsAlwaysCritical() {
    Finding finding = AttentionPolicy.assess(done(Progress.FAILED, 5, false), T, NOW).orElseThrow();
    assertEquals(Kind.FAILED_STOP, finding.kind());
    assertEquals(Severity.CRITICAL, finding.severity());
  }

  @Test
  void aSkippedStopAndAnOnTimeStopNeedNothing() {
    assertEquals(Optional.empty(), AttentionPolicy.assess(done(Progress.SKIPPED, 5, false), T, NOW));
    assertEquals(Optional.empty(), AttentionPolicy.assess(open(30, Optional.empty(), 120), T, NOW));
    assertEquals(Optional.empty(), AttentionPolicy.assess(open(30, Optional.of(40L), 120), T, NOW), "ten minutes late is under the threshold");
  }

  @Test
  void aStopArrivingAfterItsWindowIsAtRiskAndCriticalOnceTheWindowIsAboutToClose() {
    Finding far = AttentionPolicy.assess(open(60, Optional.of(200L), 180), T, NOW).orElseThrow();
    assertEquals(Kind.WINDOW_AT_RISK, far.kind());
    assertEquals(Severity.HIGH, far.severity());
    assertEquals(Optional.of(180L), far.minutesLeft());

    Finding near = AttentionPolicy.assess(open(20, Optional.of(70L), 48), T, NOW).orElseThrow();
    assertEquals(Kind.WINDOW_AT_RISK, near.kind());
    assertEquals(Severity.CRITICAL, near.severity());
  }

  @Test
  void aPlannedArrivalAfterTheWindowIsAtRiskEvenBeforeAnyDelayIsSeen() {
    assertEquals(Kind.WINDOW_AT_RISK, AttentionPolicy.assess(open(90, Optional.empty(), 60), T, NOW).orElseThrow().kind());
  }

  @Test
  void aStopLateButInsideItsWindowIsWorthKnowing() {
    Finding finding = AttentionPolicy.assess(open(30, Optional.of(50L), 120), T, NOW).orElseThrow();
    assertEquals(Kind.RUNNING_LATE, finding.kind());
    assertEquals(Severity.MEDIUM, finding.severity());
  }

  @Test
  void proofIsChasedOnlyAfterTheGraceAndNeverOnceCaptured() {
    assertEquals(Optional.empty(), AttentionPolicy.assess(done(Progress.DELIVERED, 5, false), T, NOW), "inside the grace");
    assertEquals(Kind.PROOF_OWED, AttentionPolicy.assess(done(Progress.DELIVERED, 15, false), T, NOW).orElseThrow().kind());
    assertEquals(Optional.empty(), AttentionPolicy.assess(done(Progress.DELIVERED, 15, true), T, NOW));
  }

  @Test
  void aDepotCanTightenItsOwnThresholds() {
    AttentionThresholds strict = new AttentionThresholds(5, 60, 10, 10, 2);
    assertEquals(Kind.RUNNING_LATE, AttentionPolicy.assess(open(30, Optional.of(40L), 120), strict, NOW).orElseThrow().kind());
    assertThrows(IllegalArgumentException.class, () -> new AttentionThresholds(0, 60, 10, 10, 2));
  }

  @Test
  void onlyCriticalItemsAreRemindedAfterTheWaitAndNeverPastTheCap() {
    Instant raised = in(-12);
    assertTrue(ReminderPolicy.due(Severity.CRITICAL, raised, Optional.empty(), 0, T, NOW));
    assertFalse(ReminderPolicy.due(Severity.CRITICAL, in(-5), Optional.empty(), 0, T, NOW), "too soon");
    assertFalse(ReminderPolicy.due(Severity.HIGH, raised, Optional.empty(), 0, T, NOW), "not critical");
    assertFalse(ReminderPolicy.due(Severity.CRITICAL, raised, Optional.of(in(-3)), 1, T, NOW), "reminded three minutes ago");
    assertTrue(ReminderPolicy.due(Severity.CRITICAL, raised, Optional.of(in(-11)), 1, T, NOW));
    assertFalse(ReminderPolicy.due(Severity.CRITICAL, raised, Optional.of(in(-30)), 2, T, NOW), "the cap is reached");
  }
}
