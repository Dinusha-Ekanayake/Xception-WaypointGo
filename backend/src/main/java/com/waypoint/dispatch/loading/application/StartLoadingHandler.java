package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.identity.contract.PersonQuery;
import com.waypoint.dispatch.identity.contract.PersonQuery.PersonView;
import com.waypoint.dispatch.loading.application.LoadingMessages.Loaded;
import com.waypoint.dispatch.loading.contract.LoadingCommands;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingStarted;
import com.waypoint.dispatch.loading.domain.LoadingSession;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Take a trip (Figma 01 "Take trip"). It locks to this loader until release or
 * hand back (R-LOD-11). The first take announces loading.started, which stops
 * the trip's orders being amended (ORD-06).
 */
@Component
public class StartLoadingHandler implements CommandHandler {
  private final JdbcLoadingRepository trips;
  private final PersonQuery people;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public StartLoadingHandler(
      JdbcLoadingRepository trips, PersonQuery people, EventPublisher events, Metrics metrics, Clock clock) {
    this.trips = trips;
    this.people = people;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return LoadingCommands.START;
  }

  @Override
  public String action() {
    return LoadingCommands.START;
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
    Instant now = clock.now();
    Loaded loaded = LoadingMessages.load(trips, tripId, expected);

    PersonView person =
        people.person(actor.userId()).orElse(new PersonView(actor.userId(), "Loader", Optional.empty()));
    boolean first = loaded.session().neverStarted();
    LoadingSession before = loaded.session();
    LoadingSession next =
        before.take(actor.userId(), person.displayName(), person.employeeCode(), now).session().active(now);
    boolean lapsed =
        before.holder().map(h -> !h.userId().equals(actor.userId())).orElse(false);

    long version = trips.updateSession(next, expected, now, Optional.empty());
    if (first) {
      events.publish(
          actor, new LoadingStarted(tripId, loaded.trip().planId(), loaded.trip().vehicleId()));
    }
    metrics.increment(
        "waypoint.loading.taken", "first", String.valueOf(first), "lapsed", String.valueOf(lapsed));
    return LoadingMessages.result(tripId, version, next);
  }
}
