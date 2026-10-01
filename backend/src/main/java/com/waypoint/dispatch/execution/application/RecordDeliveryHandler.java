package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryFailed;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.FailureReason;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The outcome of a stop: delivered, partial, or failed (R-EXE-01). Recorded
 * once. Ordering, Warehouse, Receipt and Issues learn of it from the event,
 * never from this module's tables.
 */
@Component
public class RecordDeliveryHandler extends DeliveryCommandHandler {
  private final JdbcDeliveryRepository deliveries;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public RecordDeliveryHandler(
      JdbcDeliveryRepository deliveries, EventPublisher events, Metrics metrics, Clock clock) {
    super(ExecutionCommands.RECORD);
    this.deliveries = deliveries;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = ExecutionMessages.expectedVersion(command);
    CommandPayload payload = CommandPayload.of(command);
    UUID deliveryId = payload.uuid("deliveryId");
    DeliveryOutcome outcome = outcome(payload.requiredText("outcome"));
    Optional<String> reason = Optional.ofNullable(payload.text("reason"));
    Optional<String> note = Optional.ofNullable(payload.text("dispositionNote"));
    Instant now = clock.now();

    DeliveryRecord before = ExecutionMessages.load(deliveries, deliveryId, expected);
    DeliveryRecord after;
    try {
      after =
          outcome == DeliveryOutcome.FAILED
              ? before.fail(FailureReason.parse(payload.requiredText("reason")), note, now)
              : before.complete(
                  outcome, ExecutionMessages.optionalInt(command, "deliveredUnits"), reason, note, now);
    } catch (DomainException e) {
      metrics.increment(
          "waypoint.execution.record_refused", "rule", e.rules().isEmpty() ? "none" : e.rules().get(0));
      throw e;
    }
    long version = deliveries.save(after, expected, ExecutionMessages.stamp(actor, command, now));

    if (outcome == DeliveryOutcome.FAILED) {
      events.publish(
          actor,
          new DeliveryFailed(
              deliveryId, after.orderId(), after.tripId(), after.outletId(), after.depotCode(),
              after.failureReason().orElseThrow(), now));
    } else {
      events.publish(
          actor,
          new DeliveryCompleted(
              deliveryId, after.orderId(), after.tripId(), after.outletId(), outcome, after.deliveredUnits(),
              now, after.lateMinutes().filter(m -> m > 0)));
    }
    metrics.increment(
        "waypoint.execution.outcome",
        "outcome", outcome.name().toLowerCase(Locale.ROOT),
        "reason", outcome == DeliveryOutcome.FAILED ? after.failureReason().orElse("none") : "none",
        "late", String.valueOf(after.isLate()),
        "proof", after.proofId().isPresent() ? "present" : "absent");
    return ExecutionMessages.result(after, version);
  }

  private static DeliveryOutcome outcome(String value) {
    return switch (value.trim().toUpperCase(Locale.ROOT)) {
      case "DELIVERED" -> DeliveryOutcome.DELIVERED;
      case "PARTIAL" -> DeliveryOutcome.PARTIAL;
      case "FAILED" -> DeliveryOutcome.FAILED;
      default -> throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "outcome must be DELIVERED, PARTIAL or FAILED");
    };
  }
}
