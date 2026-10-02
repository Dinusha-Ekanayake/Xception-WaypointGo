package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One order at one stop of a released trip, and what happened there.
 *
 * <p>Immutable and free of persistence. Each operation either refuses with the
 * rule it breaks or returns the next state; the version guard is the
 * repository's. Time is always a parameter, and it is always the server's
 * (R-EXE-10): the device's clock is kept beside it and decides nothing.
 *
 * <p>The state graph, which the status CHECK in the table cannot express:
 *
 * <pre>
 *   PENDING ──arrive──▶ ARRIVED ──complete──▶ DELIVERED | PARTIAL
 *      │                   └──────fail──────▶ FAILED
 *      ├──fail──▶ FAILED      (never reached the outlet)
 *      └──skip──▶ SKIPPED     (the plan was revised and the stop left the trip)
 * </pre>
 *
 * <p>An outcome is recorded once. Nothing leaves DELIVERED, PARTIAL, FAILED or
 * SKIPPED; proof is attached beside the outcome and never replaces it.
 *
 * @param mallOutlet the outlet takes goods only inside its window (R-PLN-14)
 * @param timingUncertain the device and the server disagreed about when the
 *     arrival happened by more than {@link #CLOCK_TOLERANCE}, usually because it
 *     was recorded offline and replayed later
 */
public record DeliveryRecord(
    UUID deliveryId,
    UUID tripId,
    UUID orderId,
    String outletId,
    String depotCode,
    String vehicleId,
    LocalDate serviceDate,
    int sequence,
    int itemCount,
    LocalTime plannedArrival,
    ServiceWindow window,
    boolean mallOutlet,
    DeliveryOutcome outcome,
    Optional<Instant> startedAt,
    Optional<Instant> arrivedAt,
    Optional<Instant> serviceStartedAt,
    Optional<Instant> completedAt,
    Optional<Integer> waitMinutes,
    Optional<Integer> lateMinutes,
    Optional<String> lateReason,
    boolean timingUncertain,
    Optional<Integer> deliveredUnits,
    Optional<String> failureReason,
    Optional<String> dispositionNote,
    boolean lowEvidence,
    Optional<UUID> proofId,
    long rowVersion) {

  /** Beyond this, the two clocks are not describing the same moment. */
  public static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);

  /** Why a late record has no reason from the driver: its time is the replay's, not the stop's. */
  public static final String RECORDED_AFTER_RECONNECT = "recorded_after_reconnect";

  public static final String REPLANNED = "replanned";

  public static final int NOTE_LIMIT = 500;

  /** A stop as the released trip described it, with nothing done yet. */
  public static DeliveryRecord released(
      UUID deliveryId,
      UUID tripId,
      UUID orderId,
      String outletId,
      String depotCode,
      String vehicleId,
      LocalDate serviceDate,
      int sequence,
      int itemCount,
      LocalTime plannedArrival,
      ServiceWindow window,
      boolean mallOutlet) {
    return new DeliveryRecord(
        deliveryId, tripId, orderId, outletId, depotCode, vehicleId, serviceDate, sequence, itemCount,
        plannedArrival, window, mallOutlet, DeliveryOutcome.PENDING,
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty(), Optional.empty(),
        false, Optional.empty(), 1);
  }

  public Instant plannedArrivalAt() {
    return serviceDate.atTime(plannedArrival).atZone(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE).toInstant();
  }

  public boolean isLate() {
    return lateMinutes.orElse(0) > 0;
  }

  public boolean isFinished() {
    return outcome != DeliveryOutcome.PENDING && outcome != DeliveryOutcome.ARRIVED;
  }

  /** The driver sets off for this stop. */
  public DeliveryRecord start(Instant now) {
    requireOutcome("started", DeliveryOutcome.PENDING);
    if (startedAt.isPresent()) {
      throw refused("This stop was already started", "R-EXE-01");
    }
    return with(outcome, Optional.of(now), arrivedAt, serviceStartedAt, completedAt, waitMinutes, lateMinutes,
        lateReason, timingUncertain, deliveredUnits, failureReason, dispositionNote, lowEvidence, proofId);
  }

  /**
   * The vehicle is at the outlet. Waiting and lateness are settled here, against
   * the window, from the server's clock (R-EXE-04, R-EXE-14, R-EXE-10).
   *
   * @param deviceArrivedAt what the device thought the time was; forensics only
   */
  public DeliveryRecord arrive(Instant now, Optional<Instant> deviceArrivedAt) {
    requireOutcome("arrived at", DeliveryOutcome.PENDING);
    Instant opens = window.opensAt(serviceDate);
    int late = LatenessPolicy.lateMinutes(now, window.closesAt(serviceDate));
    boolean uncertain =
        deviceArrivedAt
            .map(device -> Duration.between(device, now).abs().compareTo(CLOCK_TOLERANCE) > 0)
            .orElse(false);
    return with(
        DeliveryOutcome.ARRIVED,
        startedAt.or(() -> Optional.of(now)),
        Optional.of(now),
        Optional.of(LatenessPolicy.serviceStart(now, opens)),
        completedAt,
        Optional.of(LatenessPolicy.waitMinutes(now, opens)),
        Optional.of(late),
        late > 0 && uncertain ? Optional.of(RECORDED_AFTER_RECONNECT) : Optional.empty(),
        uncertain,
        deliveredUnits, failureReason, dispositionNote, lowEvidence, proofId);
  }

  /**
   * The goods were handed over, all of them or some.
   *
   * @param units for {@code PARTIAL}, how many units were delivered: at least
   *     one and fewer than the order's
   * @param reason why the stop was late (R-EXE-05) or why the delivery was partial
   * @param dispositionNote for {@code PARTIAL}, what happened to the rest (R-EXE-12)
   */
  public DeliveryRecord complete(
      DeliveryOutcome result,
      Optional<Integer> units,
      Optional<String> reason,
      Optional<String> dispositionNote,
      Instant now) {
    if (result != DeliveryOutcome.DELIVERED && result != DeliveryOutcome.PARTIAL) {
      throw new IllegalArgumentException("complete() records DELIVERED or PARTIAL, not " + result);
    }
    if (outcome == DeliveryOutcome.PENDING) {
      throw refused("Record the arrival at this stop before its delivery", "R-EXE-04");
    }
    requireOutcome("delivered", DeliveryOutcome.ARRIVED);
    if (mallOutlet && isLate()) {
      throw refused(
          "The mall's delivery window has closed, so nothing can be unloaded here."
              + " Record the stop as failed with reason " + FailureReason.MALL_WINDOW_CLOSED.code(),
          "R-PLN-14");
    }
    Optional<String> cleanReason = clean(reason, "reason");
    Optional<String> cleanNote = clean(dispositionNote, "dispositionNote");

    int delivered;
    if (result == DeliveryOutcome.PARTIAL) {
      delivered =
          units.orElseThrow(
              () -> refused("deliveredUnits is required for a partial delivery", "R-EXE-01"));
      if (delivered < 1 || delivered >= itemCount) {
        throw refused(
            "A partial delivery is at least 1 and fewer than the order's " + itemCount + " units",
            "R-EXE-01");
      }
      if (cleanReason.isEmpty()) {
        throw refused("Say why the delivery was partial", "R-EXE-01");
      }
      if (cleanNote.isEmpty()) {
        throw refused("Say what happened to the goods that were not delivered", "R-EXE-12");
      }
    } else {
      if (units.isPresent() && units.get() != itemCount) {
        throw refused(
            "A full delivery is all " + itemCount + " units; record fewer as a partial delivery",
            "R-EXE-01");
      }
      delivered = itemCount;
    }

    Optional<String> late = lateReason;
    if (isLate() && late.isEmpty()) {
      // R-EXE-05: delivered all the same, and the lateness carries a reason.
      late = Optional.of(cleanReason.orElseThrow(
          () -> refused("This stop arrived after the window closed. Say why it was late", "R-EXE-05")));
    }
    return with(result, startedAt, arrivedAt, serviceStartedAt, Optional.of(now), waitMinutes, lateMinutes,
        late, timingUncertain, Optional.of(delivered),
        result == DeliveryOutcome.PARTIAL ? cleanReason : Optional.empty(),
        cleanNote, lowEvidence, proofId);
  }

  /**
   * Nothing was delivered. Allowed before arrival too: a breakdown or a blocked
   * road fails a stop the vehicle never reached.
   *
   * @param dispositionNote what happened to the goods; there is no returns
   *     workflow, so this note is the whole record of it (R-EXE-12)
   */
  public DeliveryRecord fail(FailureReason reason, Optional<String> dispositionNote, Instant now) {
    if (isFinished()) {
      throw alreadyRecorded("failed");
    }
    Optional<String> note = clean(dispositionNote, "dispositionNote");
    if (note.isEmpty()) {
      throw refused("Say what happened to the goods", "R-EXE-12");
    }
    return with(DeliveryOutcome.FAILED, startedAt, arrivedAt, serviceStartedAt, Optional.of(now), waitMinutes,
        lateMinutes, lateReason, timingUncertain, Optional.of(0), Optional.of(reason.code()), note,
        lowEvidence, proofId);
  }

  /** The plan was revised and this stop is no longer on the trip. Only a stop nobody has reached. */
  public DeliveryRecord skip(Instant now) {
    requireOutcome("skipped", DeliveryOutcome.PENDING);
    return with(DeliveryOutcome.SKIPPED, startedAt, arrivedAt, serviceStartedAt, Optional.of(now), waitMinutes,
        lateMinutes, lateReason, timingUncertain, deliveredUnits, Optional.of(REPLANNED), dispositionNote,
        lowEvidence, proofId);
  }

  /** Proof is evidence about a stop the vehicle reached; it sits beside the outcome (R-EXE-01). */
  public DeliveryRecord withProof(UUID newProofId, ProofOfDelivery proof) {
    if (outcome == DeliveryOutcome.PENDING || outcome == DeliveryOutcome.SKIPPED) {
      throw refused("Proof is captured at a stop the vehicle has reached", "R-EXE-01");
    }
    return with(outcome, startedAt, arrivedAt, serviceStartedAt, completedAt, waitMinutes, lateMinutes,
        lateReason, timingUncertain, deliveredUnits, failureReason, dispositionNote, proof.lowEvidence(),
        Optional.of(newProofId));
  }

  private void requireOutcome(String verb, DeliveryOutcome required) {
    if (outcome != required) {
      throw isFinished()
          ? alreadyRecorded(verb)
          : refused("This stop cannot be " + verb + " while it is " + outcome, "R-EXE-01");
    }
  }

  /** One outcome per stop, recorded once (MODULES "Execution" invariants). */
  private DomainException alreadyRecorded(String verb) {
    return refused(
        "This stop is already recorded as " + outcome + " and cannot be " + verb, "R-EXE-01");
  }

  private static DomainException refused(String message, String rule) {
    return new DomainException(ErrorCode.CONSTRAINT_VIOLATED, message, List.of(rule));
  }

  private static Optional<String> clean(Optional<String> text, String field) {
    Optional<String> trimmed = text.map(String::trim).filter(t -> !t.isEmpty());
    if (trimmed.filter(t -> t.length() > NOTE_LIMIT).isPresent()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, field + " is at most " + NOTE_LIMIT + " characters");
    }
    return trimmed;
  }

  private DeliveryRecord with(
      DeliveryOutcome outcome,
      Optional<Instant> startedAt,
      Optional<Instant> arrivedAt,
      Optional<Instant> serviceStartedAt,
      Optional<Instant> completedAt,
      Optional<Integer> waitMinutes,
      Optional<Integer> lateMinutes,
      Optional<String> lateReason,
      boolean timingUncertain,
      Optional<Integer> deliveredUnits,
      Optional<String> failureReason,
      Optional<String> dispositionNote,
      boolean lowEvidence,
      Optional<UUID> proofId) {
    return new DeliveryRecord(
        deliveryId, tripId, orderId, outletId, depotCode, vehicleId, serviceDate, sequence, itemCount,
        plannedArrival, window, mallOutlet, outcome, startedAt, arrivedAt, serviceStartedAt, completedAt,
        waitMinutes, lateMinutes, lateReason, timingUncertain, deliveredUnits, failureReason,
        dispositionNote, lowEvidence, proofId, rowVersion);
  }
}
