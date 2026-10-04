package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.loading.application.LoadingMessages.Loaded;
import com.waypoint.dispatch.loading.contract.LoadingCommands;
import com.waypoint.dispatch.loading.domain.LoadingSession;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Let go of a trip (Figma E7 "Hand back VEH043?"). The checks already made keep
 * the loader's name and time, and another loader can take the trip (R-LOD-11).
 */
@Component
public class HandBackHandler implements CommandHandler {
  private final JdbcLoadingRepository trips;
  private final Metrics metrics;
  private final Clock clock;

  public HandBackHandler(JdbcLoadingRepository trips, Metrics metrics, Clock clock) {
    this.trips = trips;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return LoadingCommands.HAND_BACK;
  }

  @Override
  public String action() {
    return LoadingCommands.HAND_BACK;
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
    UUID tripId = CommandPayload.of(command).uuid("tripId");
    Loaded loaded = LoadingMessages.load(trips, tripId, expected);
    LoadingSession next = loaded.session().handBack(actor.userId()).session();
    long version = trips.updateSession(next, expected, clock.now(), Optional.empty());
    metrics.increment("waypoint.loading.handed_back");
    return LoadingMessages.result(tripId, version, next);
  }
}
