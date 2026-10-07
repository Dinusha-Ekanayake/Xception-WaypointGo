package com.waypoint.dispatch.execution.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.execution.domain.EtaPolicy.StopTimes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Roadmap T1-03: the expected arrival by propagation, from the last stop the vehicle reached. */
class EtaPropagationTest {
  private static final UUID A = new UUID(0, 1);
  private static final UUID B = new UUID(0, 2);
  private static final UUID C = new UUID(0, 3);

  /** 06:00 on the day, as an instant: the tests read in whole minutes after it. */
  private static Instant at(int minutes) {
    return Instant.parse("2026-10-07T00:30:00Z").plusSeconds(60L * minutes);
  }

  private static StopTimes planned(UUID id, int arrival, int open, Integer service) {
    return new StopTimes(id, at(arrival), at(open), Optional.ofNullable(service), Optional.empty(), Optional.empty(), false);
  }

  private static StopTimes left(StopTimes s, int arrived, int left) {
    return new StopTimes(
        s.deliveryId(), s.plannedArrival(), s.windowOpen(), s.serviceMinutes(), Optional.of(at(arrived)),
        Optional.of(at(left)), true);
  }

  private static StopTimes there(StopTimes s, int arrived) {
    return new StopTimes(
        s.deliveryId(), s.plannedArrival(), s.windowOpen(), s.serviceMinutes(), Optional.of(at(arrived)),
        Optional.empty(), false);
  }

  // Planned: A at 0 for 20 min, drive 20; B at 40 for 15 min, drive 25; C at 80.
  private final StopTimes a = planned(A, 0, 0, 20);
  private final StopTimes b = planned(B, 40, 0, 15);
  private final StopTimes c = planned(C, 80, 0, 10);

  @Test
  void beforeTheFirstStopsPlannedTimeThePlanStands() {
    // A is planned at 0: at 0 nothing is late yet.
    assertTrue(EtaPolicy.propagate(List.of(a, b, c), at(0)).isEmpty());
  }

  @Test
  void aFirstStopPastItsTimeWithNoArrivalIsExpectedNowAndTheRestFollow() {
    // At 30 the vehicle has not reached A (planned 0): A now, B at 30 + 20 + 20, C at 70 + 15 + 25.
    Map<UUID, Instant> eta = EtaPolicy.propagate(List.of(a, b, c), at(30));
    assertEquals(at(30), eta.get(A));
    assertEquals(at(70), eta.get(B));
    assertEquals(at(110), eta.get(C));
  }

  @Test
  void theDelayAStoreIsToldCountsFromItsWindowWhenThePlanWasEarlier() {
    // Planned 04:23 (minute 0 + 0), window opens at minute 37; expected at minute 104: 67 late, not 104.
    assertEquals(67, EtaPolicy.delayMinutes(at(104), at(0), at(37)));
    assertEquals(104, EtaPolicy.delayMinutes(at(104), at(0), at(0)));
  }

  @Test
  void aLongUnloadMovesEveryStopAfterIt() {
    // A took 45 min, not 20: left at 45, so B at 65 and C at 65 + 15 + 25 = 105.
    Map<UUID, Instant> eta = EtaPolicy.propagate(List.of(left(a, 0, 45), b, c), at(46));
    assertEquals(at(65), eta.get(B));
    assertEquals(at(105), eta.get(C));
  }

  @Test
  void aVehicleHeldUpOnTheRoadIsSeenLateWithoutAnyArrival() {
    // Left A on time at 20; at 70 still not at B (due 40): B is expected now at the earliest.
    Map<UUID, Instant> eta = EtaPolicy.propagate(List.of(left(a, 0, 20), b, c), at(70));
    assertEquals(at(70), eta.get(B));
    assertEquals(at(110), eta.get(C));
  }

  @Test
  void stillAtAStopItLeavesWhenItsPlannedMinutesAreUpAndNotBeforeNow() {
    Map<UUID, Instant> eta = EtaPolicy.propagate(List.of(there(a, 10), b, c), at(12));
    assertEquals(at(50), eta.get(B), "arrived 10 late, 20 minutes there, 20 to drive");
    eta = EtaPolicy.propagate(List.of(there(a, 10), b, c), at(40));
    assertEquals(at(60), eta.get(B), "still there at 40: it leaves now at the earliest");
  }

  @Test
  void aheadOfPlanIsNeverAnnouncedAsEarlyAndAClosedWindowIsWaitedFor() {
    StopTimes lateOpen = planned(B, 40, 50, 15);
    Map<UUID, Instant> eta = EtaPolicy.propagate(List.of(left(a, 0, 5), lateOpen, c), at(6));
    assertEquals(at(40), eta.get(B), "ahead of plan: the planned time");
    // B waits for its window at 50 and serves 15, as the plan did, so C stays at its planned 80.
    assertEquals(at(80), eta.get(C));
  }

  @Test
  void withoutThePlansMinutesTheDelayIsCarriedForwardAsBefore() {
    StopTimes a0 = planned(A, 0, 0, null);
    StopTimes b0 = planned(B, 40, 0, null);
    StopTimes c0 = planned(C, 80, 0, null);
    Map<UUID, Instant> eta = EtaPolicy.propagate(List.of(there(a0, 15), b0, c0), at(16));
    assertEquals(at(55), eta.get(B));
    assertEquals(at(95), eta.get(C));
  }

  @Test
  void aStoreIsToldAgainOnlyWhenItsTimeMovesTenMinutes() {
    assertFalse(EtaPolicy.worthAnnouncing(at(49), at(40)));
    assertTrue(EtaPolicy.worthAnnouncing(at(50), at(40)));
    assertTrue(EtaPolicy.worthAnnouncing(at(30), at(40)), "back on time is news too");
  }

  @Test
  void theExpectedMinutesAtAStopDriveTheEstimateAndThePlansMinutesTheDrive() {
    // T1-04: A is expected to take 35 min, not the plan's 20. Arrived on time at 0: leaves at 35, B at 55.
    StopTimes slowA = new StopTimes(A, at(0), at(0), Optional.of(20), Optional.of(at(0)), Optional.empty(), false,
        Optional.of(java.time.Duration.ofMinutes(35)));
    Map<UUID, Instant> eta = EtaPolicy.propagate(List.of(slowA, b, c), at(1));
    assertEquals(at(55), eta.get(B));
    // B expected 15 as planned: C at 55 + 15 + 25.
    assertEquals(at(95), eta.get(C));
  }

  @Test
  void withoutThePlansMinutesTheExpectedOnesAreNotCountedTwice() {
    StopTimes a0 = new StopTimes(A, at(0), at(0), Optional.empty(), Optional.of(at(0)), Optional.empty(), false,
        Optional.of(java.time.Duration.ofMinutes(20)));
    StopTimes b0 = planned(B, 40, 0, null);
    // Leaves at 20 (its expected minutes), drives the gap less those minutes (20): B at 40, on plan.
    assertEquals(at(40), EtaPolicy.propagate(List.of(a0, b0), at(1)).get(B));
  }
}
