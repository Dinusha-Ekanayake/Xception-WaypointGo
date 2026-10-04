package com.waypoint.dispatch.intelligence.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetStopView;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.Progress;
import com.waypoint.dispatch.intelligence.domain.AttentionPolicy.StopFacts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #268: a run sheet stop as the attention rule reads it, and when the watch counts as stale. */
class AttentionWatchMappingTest {
  private static final LocalDate DAY = LocalDate.of(2026, 10, 5);

  private static RunSheetStopView stop(DeliveryOutcome outcome, boolean proof) {
    return new RunSheetStopView(
        UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), "OUT001", 12, false,
        LocalTime.of(9, 30), LocalTime.of(8, 0), LocalTime.of(11, 0),
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        outcome, Optional.empty(), proof, 1L, List.of(), Optional.empty());
  }

  @Test
  void theDaysClockTimesBecomeInstantsInTheDepotsZone() {
    StopFacts facts = AttentionWatchJob.facts(stop(DeliveryOutcome.PENDING, false), DAY);
    // 09:30 and 11:00 in Colombo are 04:00 and 05:30 UTC.
    assertEquals(Instant.parse("2026-10-05T04:00:00Z"), facts.plannedArrival());
    assertEquals(Instant.parse("2026-10-05T05:30:00Z"), facts.windowClose());
  }

  @Test
  void everyOutcomeMapsToTheProgressTheRuleKnows() {
    assertEquals(Progress.OPEN, AttentionWatchJob.facts(stop(DeliveryOutcome.PENDING, false), DAY).progress());
    assertEquals(Progress.OPEN, AttentionWatchJob.facts(stop(DeliveryOutcome.ARRIVED, false), DAY).progress());
    assertEquals(Progress.DELIVERED, AttentionWatchJob.facts(stop(DeliveryOutcome.DELIVERED, true), DAY).progress());
    assertEquals(Progress.DELIVERED, AttentionWatchJob.facts(stop(DeliveryOutcome.PARTIAL, false), DAY).progress());
    assertEquals(Progress.FAILED, AttentionWatchJob.facts(stop(DeliveryOutcome.FAILED, false), DAY).progress());
    assertEquals(Progress.SKIPPED, AttentionWatchJob.facts(stop(DeliveryOutcome.SKIPPED, false), DAY).progress());
  }

  @Test
  void aWatchThatNeverLookedOrStoppedLookingIsStale() {
    Instant now = Instant.parse("2026-10-05T04:00:00Z");
    assertTrue(AttentionQuery.stale(Optional.empty(), now), "never looked");
    assertFalse(AttentionQuery.stale(Optional.of(now.minusSeconds(60)), now), "looked a minute ago");
    assertTrue(AttentionQuery.stale(Optional.of(now.minusSeconds(240)), now), "four minutes without a look");
  }
}
