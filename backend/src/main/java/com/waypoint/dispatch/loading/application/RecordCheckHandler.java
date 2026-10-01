package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.loading.application.LoadingMessages.Loaded;
import com.waypoint.dispatch.loading.contract.LoadingCommands;
import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.loading.domain.LoadingSession.Change;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tick an item as loaded, or undo a tick (Figma 02 "Tap when loaded", E5 Undo).
 * Each changed line gets a new attempt; nothing is overwritten. Without a line,
 * every unchecked line of the order is ticked.
 */
@Component
public class RecordCheckHandler implements CommandHandler {
  private final JdbcLoadingRepository trips;
  private final Metrics metrics;
  private final Clock clock;

  public RecordCheckHandler(JdbcLoadingRepository trips, Metrics metrics, Clock clock) {
    this.trips = trips;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return LoadingCommands.CHECK;
  }

  @Override
  public String action() {
    return LoadingCommands.CHECK;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.LOADING;
  }

  @Override
  public String resource(Command command) {
    return LoadingMessages.resource(command);
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = LoadingMessages.expectedVersion(command);
    CommandPayload payload = CommandPayload.of(command);
    UUID tripId = payload.uuid("tripId");
    UUID orderId = payload.uuid("orderId");
    Optional<Integer> lineNo = LoadingMessages.optionalInt(command, "lineNo");
    CheckStatus status = LoadingMessages.status(command, "status");
    Instant now = clock.now();

    Loaded loaded = LoadingMessages.load(trips, tripId, expected);
    Change change = loaded.session().check(actor.userId(), orderId, lineNo, status);

    long version = trips.updateSession(change.session().active(now), expected, now, Optional.empty());
    trips.appendChecks(
        tripId,
        loaded.trip().planVersion(),
        change.changed(),
        Optional.ofNullable(payload.text("reason")),
        Optional.empty(),
        actor.userId(),
        actor.deviceId(),
        command.commandId(),
        now,
        command.clientRecordedAt());
    metrics.increment(
        "waypoint.loading.checked", "status", JdbcLoadingRepository.code(status), "lines",
        String.valueOf(change.changed().size()));
    return LoadingMessages.result(tripId, version, change.session());
  }
}
