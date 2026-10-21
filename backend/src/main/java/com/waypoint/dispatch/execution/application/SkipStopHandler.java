package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.StopSkip;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Skip store (R-EXE-31): the dispatcher takes one stop off a trip on the road,
 * reached or not, with what happens to its order (next day or cancel) and why.
 * The driver's phone, offline or not, has its next write to the stop refused and
 * held for review; the route drops the stop on its next read.
 */
@Component
public class SkipStopHandler extends DeliveryCommandHandler {
  private final StopSkips skips;
  private final Clock clock;

  public SkipStopHandler(StopSkips skips, Clock clock) {
    super(ExecutionCommands.SKIP_STOP);
    this.skips = skips;
    this.clock = clock;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = ExecutionMessages.expectedVersion(command);
    CommandPayload payload = CommandPayload.of(command);
    DeliveryRecord stop = skips.load(payload.uuid("deliveryId"));
    StopSkip.Then then = StopSkip.Then.parse(payload.requiredText("then"));
    String reason = StopSkip.reason(payload.requiredText("reason"));
    Instant now = clock.now();

    // The stops after it no longer wait for this one: EtaRefreshJob moves their estimates within
    // two minutes, as the system. The dispatcher may write a skip and nothing else (row-level security).
    long version = skips.skip(actor, command, stop, expected, then, reason, false, now);

    Map<String, Object> result = ExecutionMessages.result(stop.skipByDispatcher(now), version);
    result.put("then", then.name());
    return result;
  }
}
