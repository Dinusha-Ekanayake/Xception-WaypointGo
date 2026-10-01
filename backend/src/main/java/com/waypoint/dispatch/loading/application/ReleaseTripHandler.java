package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.loading.application.LoadingMessages.Loaded;
import com.waypoint.dispatch.loading.contract.LoadingCommands;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.loading.domain.LoadingSession;
import com.waypoint.dispatch.loading.domain.ReleaseChecklist;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository.Release;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Release the vehicle (Figma 04 "Hold to release vehicle", E9, E11). Refused
 * while an item is still unchecked on the current plan version (R-LOD-07) or
 * the checklist fails (R-LOD-10). Flagged items travel as recorded exceptions.
 *
 * <p>trip.released carries the stops, so Execution builds the driver's run sheet
 * from the event alone and Ordering moves the orders in transit.
 */
@Component
public class ReleaseTripHandler implements CommandHandler {
  private final JdbcLoadingRepository trips;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public ReleaseTripHandler(
      JdbcLoadingRepository trips, EventPublisher events, Metrics metrics, Clock clock) {
    this.trips = trips;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return LoadingCommands.RELEASE;
  }

  @Override
  public String action() {
    return LoadingCommands.RELEASE;
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
    ReleaseChecklist checklist =
        new ReleaseChecklist(
            LoadingMessages.requiredFlag(command, "doorsSealed"),
            LoadingMessages.requiredFlag(command, "ordersSecured"),
            LoadingMessages.requiredFlag(command, "driverPresent"));
    Instant now = clock.now();

    Loaded loaded = LoadingMessages.load(trips, tripId, expected);
    LoadingSession released;
    try {
      released = loaded.session().release(actor.userId(), checklist);
    } catch (DomainException e) {
      metrics.increment("waypoint.loading.release_refused", "rule",
          e.violations().isEmpty() ? "none" : e.violations().get(0));
      throw e;
    }

    long version =
        trips.updateSession(
            released,
            expected,
            now,
            Optional.of(new Release(actor.userId(), now)));
    var trip = loaded.trip();
    events.publish(
        actor,
        new TripReleased(
            tripId, trip.planId(), trip.planVersion(), trip.vehicleId(), trip.depotCode(),
            trip.serviceDate(), trips.releasedStops(tripId)));
    metrics.increment("waypoint.loading.released");
    return LoadingMessages.result(tripId, version, released);
  }
}
