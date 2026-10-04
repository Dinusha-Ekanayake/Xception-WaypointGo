package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.StoreAnswerWaiver;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.domain.Actor;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The driver moves on from a handed-over stop before the store answered
 * (issue #21, store-led handover). Never a gate (R-RCP-09): the store can still
 * answer afterwards, and the delivery stands as recorded. The decision and its
 * reason are kept beside the stop (rule 8) and shown on the run sheet, where the
 * dispatcher sees it. A disagreement also raises an issue, from the phone.
 */
@Component
public class LeaveWithoutStoreAnswerHandler extends DeliveryCommandHandler {
  private final JdbcDeliveryRepository deliveries;
  private final Metrics metrics;
  private final Clock clock;

  public LeaveWithoutStoreAnswerHandler(JdbcDeliveryRepository deliveries, Metrics metrics, Clock clock) {
    super(ExecutionCommands.LEAVE_WITHOUT_STORE_ANSWER);
    this.deliveries = deliveries;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = ExecutionMessages.expectedVersion(command);
    CommandPayload payload = CommandPayload.of(command);
    UUID deliveryId = payload.uuid("deliveryId");
    StoreAnswerWaiver.Reason reason = StoreAnswerWaiver.Reason.parse(payload.requiredText("reason"));
    Instant now = clock.now();

    DeliveryRecord stop = ExecutionMessages.load(deliveries, deliveryId, expected);
    StoreAnswerWaiver.requireHandedOver(stop.outcome());
    if (!deliveries.insertStoreAnswerWaiver(
        deliveryId, reason.code(), command.commandId(), ExecutionMessages.stamp(actor, command, now))) {
      throw new DomainException(ErrorCode.CONFLICT, "This stop already says why the driver moved on");
    }
    long version = deliveries.touch(deliveryId, expected);
    metrics.increment("waypoint.execution.store_answer_waived", "reason", reason.code());

    Map<String, Object> result = ExecutionMessages.result(stop, version);
    result.put("storeAnswerWaived", reason.code());
    return result;
  }
}
