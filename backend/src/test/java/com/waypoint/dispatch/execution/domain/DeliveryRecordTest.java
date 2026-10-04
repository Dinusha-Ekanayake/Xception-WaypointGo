package com.waypoint.dispatch.execution.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The stop state machine and its timing rules, with no database and no clock. */
class DeliveryRecordTest {
  private static final LocalDate DAY = LocalDate.parse("2027-03-01");
  private static final ServiceWindow WINDOW = new ServiceWindow(LocalTime.of(8, 0), LocalTime.of(12, 0));

  private static Instant at(String time) {
    return DAY.atTime(LocalTime.parse(time)).atZone(Clock.OPERATING_ZONE).toInstant();
  }

  private static DeliveryRecord stop(boolean mall) {
    return DeliveryRecord.released(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OUT001", "KDY", "VEH001", DAY, 1, 40,
        LocalTime.of(9, 30), WINDOW, mall);
  }

  private static DeliveryRecord stop() {
    return stop(false);
  }

  private static String rule(Runnable action) {
    DomainException e = assertThrows(DomainException.class, action::run);
    assertEquals(ErrorCode.CONSTRAINT_VIOLATED, e.code(), e.getMessage());
    return e.rules().get(0);
  }

  // ---- arrival: waiting and lateness ----

  @Test
  void anEarlyArrivalWaitsAndServiceStartsWhenTheWindowOpens() {
    // EXE-05, EXE-18, R-EXE-04
    DeliveryRecord arrived = stop().arrive(at("07:25"), Optional.empty());

    assertEquals(DeliveryOutcome.ARRIVED, arrived.outcome());
    assertEquals(35, arrived.waitMinutes().orElseThrow());
    assertEquals(at("08:00"), arrived.serviceStartedAt().orElseThrow(), "service never starts at an early arrival");
    assertEquals(at("07:25"), arrived.arrivedAt().orElseThrow(), "and the arrival itself is kept as it was");
    assertEquals(0, arrived.lateMinutes().orElseThrow());
  }

  @Test
  void behindPlanButInsideTheWindowIsNotLate() {
    // EXE-16, R-EXE-14: planned 09:30, arrived 11:50, window closes 12:00.
    DeliveryRecord arrived = stop().arrive(at("11:50"), Optional.empty());

    assertFalse(arrived.isLate());
    assertEquals(0, arrived.waitMinutes().orElseThrow());
    assertEquals(at("11:50"), arrived.serviceStartedAt().orElseThrow());
  }

  @Test
  void onPlanButAfterTheWindowClosedIsLate() {
    // EXE-17: the plan said 12:20 and the vehicle made it; the window shut at 12:00.
    DeliveryRecord planned =
        DeliveryRecord.released(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OUT001", "KDY", "VEH001", DAY, 1, 40,
            LocalTime.of(12, 20), WINDOW, false);

    DeliveryRecord arrived = planned.arrive(at("12:20"), Optional.empty());

    assertTrue(arrived.isLate());
    assertEquals(20, arrived.lateMinutes().orElseThrow());
  }

  @Test
  void oneSecondPastTheCloseIsLateAndTheCloseItselfIsNot() {
    assertFalse(stop().arrive(at("12:00"), Optional.empty()).isLate());
    assertEquals(1, stop().arrive(at("12:00").plusSeconds(1), Optional.empty()).lateMinutes().orElseThrow());
  }

  @Test
  void theServerClockDecidesAndADeviceThatDisagreesIsOnlyRemembered() {
    // EXE-12, R-EXE-10: the device says 09:00, the server accepts the command at 12:30.
    DeliveryRecord arrived = stop().arrive(at("12:30"), Optional.of(at("09:00")));

    assertEquals(at("12:30"), arrived.arrivedAt().orElseThrow());
    assertEquals(30, arrived.lateMinutes().orElseThrow());
    assertTrue(arrived.timingUncertain());
    assertEquals(
        DeliveryRecord.RECORDED_AFTER_RECONNECT,
        arrived.lateReason().orElseThrow(),
        "lateness that may be the replay's, not the stop's, says so");
  }

  @Test
  void aDeviceWithinToleranceIsNotUncertain() {
    assertFalse(stop().arrive(at("09:00"), Optional.of(at("09:04"))).timingUncertain());
    assertFalse(stop().arrive(at("09:00"), Optional.empty()).timingUncertain());
    assertTrue(stop().arrive(at("09:00"), Optional.of(at("09:06"))).timingUncertain());
  }

  // ---- outcomes ----

  @Test
  void aFullDeliveryRecordsEveryUnit() {
    DeliveryRecord done =
        stop().arrive(at("09:00"), Optional.empty())
            .complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.empty(), Optional.empty(), at("09:20"));

    assertEquals(DeliveryOutcome.DELIVERED, done.outcome());
    assertEquals(40, done.deliveredUnits().orElseThrow());
    assertEquals(at("09:20"), done.completedAt().orElseThrow());
    assertTrue(done.isFinished());
  }

  @Test
  void aLateArrivalIsStillDeliveredAndItsLatenessCarriesAReason() {
    // EXE-06, R-EXE-05
    DeliveryRecord late = stop().arrive(at("12:40"), Optional.empty());

    assertEquals(
        "R-EXE-05",
        rule(() -> late.complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.empty(), Optional.empty(), at("12:55"))));

    DeliveryRecord done =
        late.complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.of("  road closed at Kadugannawa "), Optional.empty(), at("12:55"));
    assertEquals(DeliveryOutcome.DELIVERED, done.outcome());
    assertEquals("road closed at Kadugannawa", done.lateReason().orElseThrow());
    assertEquals(40, done.lateMinutes().orElseThrow());
  }

  @Test
  void aLateRecordWhoseTimeIsUncertainNeedsNoReasonFromTheDriver() {
    DeliveryRecord replayed = stop().arrive(at("15:00"), Optional.of(at("10:00")));

    DeliveryRecord done =
        replayed.complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.empty(), Optional.empty(), at("15:00"));

    assertEquals(DeliveryRecord.RECORDED_AFTER_RECONNECT, done.lateReason().orElseThrow());
  }

  @Test
  void aLateArrivalAtAMallCannotBeDeliveredOnlyFailed() {
    // EXE-20, R-PLN-14
    DeliveryRecord late = stop(true).arrive(at("12:10"), Optional.empty());

    assertEquals(
        "R-PLN-14",
        rule(() -> late.complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.of("traffic"), Optional.empty(), at("12:15"))));
    assertEquals(
        "R-PLN-14",
        rule(() -> late.complete(DeliveryOutcome.PARTIAL, Optional.of(3), Optional.of("traffic"), Optional.of("on board"), at("12:15"))));

    DeliveryRecord failed = late.fail(FailureReason.MALL_WINDOW_CLOSED, Optional.of("kept on the vehicle"), at("12:15"));
    assertEquals(DeliveryOutcome.FAILED, failed.outcome());
    assertEquals("mall_window_closed", failed.failureReason().orElseThrow());

    // On time, a mall outlet is delivered like any other.
    stop(true).arrive(at("11:00"), Optional.empty())
        .complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.empty(), Optional.empty(), at("11:10"));
  }

  @Test
  void aPartialDeliveryNeedsACountAReasonAndWhereTheRestWent() {
    DeliveryRecord arrived = stop().arrive(at("09:00"), Optional.empty());
    Optional<String> why = Optional.of("two cartons crushed");
    Optional<String> rest = Optional.of("returned to depot on the same vehicle");

    assertEquals("R-EXE-01", rule(() -> arrived.complete(DeliveryOutcome.PARTIAL, Optional.empty(), why, rest, at("09:20"))));
    assertEquals("R-EXE-01", rule(() -> arrived.complete(DeliveryOutcome.PARTIAL, Optional.of(0), why, rest, at("09:20"))));
    assertEquals("R-EXE-01", rule(() -> arrived.complete(DeliveryOutcome.PARTIAL, Optional.of(40), why, rest, at("09:20"))));
    assertEquals("R-EXE-01", rule(() -> arrived.complete(DeliveryOutcome.PARTIAL, Optional.of(38), Optional.of(" "), rest, at("09:20"))));
    assertEquals("R-EXE-12", rule(() -> arrived.complete(DeliveryOutcome.PARTIAL, Optional.of(38), why, Optional.empty(), at("09:20"))));

    DeliveryRecord partial = arrived.complete(DeliveryOutcome.PARTIAL, Optional.of(38), why, rest, at("09:20"));
    assertEquals(38, partial.deliveredUnits().orElseThrow());
    assertEquals("two cartons crushed", partial.failureReason().orElseThrow());
    assertEquals("returned to depot on the same vehicle", partial.dispositionNote().orElseThrow());
  }

  @Test
  void aFullDeliveryCannotClaimFewerUnits() {
    DeliveryRecord arrived = stop().arrive(at("09:00"), Optional.empty());

    assertEquals(
        "R-EXE-01",
        rule(() -> arrived.complete(DeliveryOutcome.DELIVERED, Optional.of(39), Optional.empty(), Optional.empty(), at("09:20"))));
  }

  @Test
  void aDeliveryNeedsAnArrivalFirst() {
    assertEquals(
        "R-EXE-04",
        rule(() -> stop().complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.empty(), Optional.empty(), at("09:20"))));
  }

  @Test
  void aStopCanFailBeforeItIsReachedAndTheGoodsAreAccountedFor() {
    // EXE-07, EXE-08, R-EXE-12
    assertEquals("R-EXE-12", rule(() -> stop().fail(FailureReason.VEHICLE_BREAKDOWN, Optional.empty(), at("08:30"))));

    DeliveryRecord failed = stop().fail(FailureReason.VEHICLE_BREAKDOWN, Optional.of("left on the vehicle at Kegalle"), at("08:30"));

    assertEquals(DeliveryOutcome.FAILED, failed.outcome());
    assertEquals(0, failed.deliveredUnits().orElseThrow());
    assertTrue(failed.arrivedAt().isEmpty());
  }

  @Test
  void anOutcomeIsRecordedOnce() {
    DeliveryRecord delivered =
        stop().arrive(at("09:00"), Optional.empty())
            .complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.empty(), Optional.empty(), at("09:20"));
    DeliveryRecord failed = stop().fail(FailureReason.REFUSED, Optional.of("returned"), at("09:20"));

    for (DeliveryRecord finished : List.of(delivered, failed, stop().skip(at("08:00")))) {
      assertEquals("R-EXE-01", rule(() -> finished.start(at("10:00"))));
      assertEquals("R-EXE-01", rule(() -> finished.arrive(at("10:00"), Optional.empty())));
      assertEquals("R-EXE-01", rule(() -> finished.fail(FailureReason.OTHER, Optional.of("x"), at("10:00"))));
      assertEquals("R-EXE-01", rule(() -> finished.skip(at("10:00"))));
    }
    assertEquals(
        "R-EXE-01",
        rule(() -> delivered.complete(DeliveryOutcome.PARTIAL, Optional.of(1), Optional.of("x"), Optional.of("y"), at("10:00"))));
  }

  @Test
  void startingIsOnceAndArrivingWithoutAStartStartsTheStop() {
    DeliveryRecord started = stop().start(at("08:40"));
    assertEquals(at("08:40"), started.startedAt().orElseThrow());
    assertEquals(DeliveryOutcome.PENDING, started.outcome());
    assertEquals("R-EXE-01", rule(() -> started.start(at("08:41"))));
    assertEquals(at("08:40"), started.arrive(at("09:00"), Optional.empty()).startedAt().orElseThrow());

    assertEquals(at("09:00"), stop().arrive(at("09:00"), Optional.empty()).startedAt().orElseThrow());
  }

  @Test
  void onlyAStopNobodyReachedIsSkippedByAReplan() {
    DeliveryRecord skipped = stop().start(at("08:40")).skip(at("08:50"));
    assertEquals(DeliveryOutcome.SKIPPED, skipped.outcome());
    assertEquals(DeliveryRecord.REPLANNED, skipped.failureReason().orElseThrow());

    assertEquals("R-EXE-01", rule(() -> stop().arrive(at("09:00"), Optional.empty()).skip(at("09:05"))));
  }

  // ---- proof ----

  @Test
  void proofSitsBesideTheOutcomeAndNeverChangesIt() {
    DeliveryRecord delivered =
        stop().arrive(at("09:00"), Optional.empty())
            .complete(DeliveryOutcome.DELIVERED, Optional.empty(), Optional.empty(), Optional.empty(), at("09:20"));
    UUID proofId = UUID.randomUUID();

    DeliveryRecord proven =
        delivered.withProof(
            proofId,
            new ProofOfDelivery(Optional.of(UUID.randomUUID()), Optional.empty(), Optional.of("N. Perera"), Optional.empty()));

    assertEquals(DeliveryOutcome.DELIVERED, proven.outcome());
    assertEquals(proofId, proven.proofId().orElseThrow());
    assertFalse(proven.lowEvidence());
    assertEquals(delivered.completedAt(), proven.completedAt());
  }

  @Test
  void aDeviceThatCannotCaptureDoesNotBlockTheWorkItLowersTheEvidence() {
    // EXE-09, R-EXE-11
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> new ProofOfDelivery(Optional.empty(), Optional.empty(), Optional.of("N. Perera"), Optional.empty()));
    assertEquals(List.of("R-EXE-11"), refused.rules());

    ProofOfDelivery fallback =
        new ProofOfDelivery(Optional.empty(), Optional.empty(), Optional.of("N. Perera"), Optional.of("camera permission denied"));
    assertTrue(fallback.lowEvidence());

    DeliveryRecord proven = stop().arrive(at("09:00"), Optional.empty()).withProof(UUID.randomUUID(), fallback);
    assertTrue(proven.lowEvidence());

    assertFalse(new ProofOfDelivery(Optional.empty(), Optional.of(UUID.randomUUID()), Optional.empty(), Optional.empty()).lowEvidence());
  }

  @Test
  void proofIsNotCapturedForAStopNobodyReached() {
    ProofOfDelivery proof = new ProofOfDelivery(Optional.of(UUID.randomUUID()), Optional.empty(), Optional.empty(), Optional.empty());

    assertEquals("R-EXE-01", rule(() -> stop().withProof(UUID.randomUUID(), proof)));
    assertEquals("R-EXE-01", rule(() -> stop().skip(at("08:00")).withProof(UUID.randomUUID(), proof)));
  }

  @Test
  void anUnknownFailureReasonIsTheClientsMistake() {
    DomainException e = assertThrows(DomainException.class, () -> FailureReason.parse("gone fishing"));
    assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    assertEquals(FailureReason.OUTLET_CLOSED, FailureReason.parse(" Outlet_Closed "));
  }
}
