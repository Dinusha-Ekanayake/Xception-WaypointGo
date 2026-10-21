package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.StopSkip;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * End trip (R-EXE-31): the dispatcher stops a trip on the road. Every stop not
 * yet done, reached or not, is skipped to the next operating day with the one
 * reason; delivered and failed stops stand. One transaction: a stop the driver
 * records meanwhile moves its version, and the whole command is refused rather
 * than ending part of the trip.
 */
@Component
public class EndTripHandler implements CommandHandler {
  private final JdbcDeliveryRepository deliveries;
  private final StopSkips skips;
  private final Clock clock;

  public EndTripHandler(JdbcDeliveryRepository deliveries, StopSkips skips, Clock clock) {
    this.deliveries = deliveries;
    this.skips = skips;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return ExecutionCommands.END_TRIP;
  }

  @Override
  public String action() {
    return ExecutionCommands.END_TRIP;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public String resource(Command command) {
    UUID trip = CommandPayload.of(command).optionalUuid("tripId");
    return trip == null ? null : "wpt:execution:trip:" + trip;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    UUID tripId = payload.uuid("tripId");
    String reason = StopSkip.reason(payload.requiredText("reason"));
    deliveries
        .tripDepot(tripId)
        .filter(deliveries::oversees)
        .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, StopSkips.NOT_YOURS));
    List<DeliveryRecord> open = deliveries.openOfTrip(tripId);
    if (open.isEmpty()) {
      throw new DomainException(ErrorCode.CONFLICT, "Every stop of this trip is already done");
    }
    Instant now = clock.now();
    for (DeliveryRecord stop : open) {
      skips.skip(actor, command, stop, stop.rowVersion(), StopSkip.Then.NEXT_DAY, reason, true, now);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tripId", tripId.toString());
    result.put("skipped", open.size());
    return result;
  }
}
