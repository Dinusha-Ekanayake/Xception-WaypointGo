package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryProblemReported;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.StopProblem;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * R-EXE-32: the driver reports a closed store or a stop that cannot unload. The
 * stop is not touched: the dispatcher decides (Skip store, R-EXE-31) and the
 * driver may go on to the next stop. A report is kept beside the stop, append
 * only. One that arrives after the stop ended (a phone that was offline while
 * the dispatcher skipped it) is kept as such and announces nothing: the driver
 * sees no conflict, and nobody is told twice.
 */
@Component
public class ReportStopProblemHandler extends DeliveryCommandHandler {
  private final JdbcDeliveryRepository deliveries;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public ReportStopProblemHandler(JdbcDeliveryRepository deliveries, EventPublisher events, Metrics metrics, Clock clock) {
    super(ExecutionCommands.REPORT_STOP_PROBLEM);
    this.deliveries = deliveries;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    UUID deliveryId = payload.uuid("deliveryId");
    StopProblem problem = StopProblem.parse(payload.requiredText("problem"));
    Instant now = clock.now();
    // The driver's own stop only; a dispatcher or a store reads and never reports here.
    DeliveryRecord stop = ExecutionMessages.loadForWrite(deliveries, deliveryId);
    boolean afterFinish = stop.isFinished();
    UUID reportId = UuidV7.generate(now, random);
    deliveries.insertStopProblem(
        reportId, deliveryId, problem.code(), afterFinish, command.commandId(), ExecutionMessages.stamp(actor, command, now));
    if (!afterFinish) {
      events.publish(
          actor,
          new DeliveryProblemReported(
              deliveryId, stop.tripId(), stop.outletId(), stop.depotCode(), stop.vehicleId(), stop.serviceDate(),
              problem.name(), now));
    }
    metrics.increment(
        "waypoint.execution.stop_problem_reported", "problem", problem.code(), "after_finish", String.valueOf(afterFinish));
    return Map.of("reportId", reportId.toString(), "problem", problem.name(), "stopEnded", afterFinish);
  }
}
