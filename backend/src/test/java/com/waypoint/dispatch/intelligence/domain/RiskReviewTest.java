package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.DomainException;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskReviewTest {
  final UUID trip = UUID.randomUUID();

  RiskReview.PlannedStop stop(int index, String arrival, String close) {
    return new RiskReview.PlannedStop(UUID.randomUUID(), trip, "VEH001", index + 1, index, "OUT00" + index,
        LocalTime.parse(arrival), LocalTime.parse(close));
  }

  @Test
  void onlyStopsAtOrAboveTheThresholdAreListedLikeliestFirst() {
    List<RiskReview.Flagged> flagged = RiskReview.flag(List.of(
        stop(0, "06:00", "11:00"),
        stop(1, "07:00", "07:00"),
        stop(2, "08:00", "07:40")), null);
    assertEquals(2, flagged.size(), "the stop with five hours to spare is not listed");
    assertTrue(flagged.get(0).percent() >= flagged.get(1).percent());
    assertEquals(-20, flagged.get(0).minutesToClose(), "planned 20 minutes after its window closes");
    assertEquals(3, flagged.get(0).stopOfTrip());
    flagged.forEach(f -> assertTrue(f.percent() >= RiskReview.THRESHOLD_PERCENT));
  }

  @Test
  void aChoiceIsKeepOrSwapAndAlwaysHasAReason() {
    assertEquals(RiskReview.Choice.SWAP, RiskReview.choice(" swap "));
    assertThrows(DomainException.class, () -> RiskReview.choice("ignore"));
    assertEquals("Store opens early", RiskReview.reason("  Store opens early "));
    assertThrows(DomainException.class, () -> RiskReview.reason("ok"));
  }
}
