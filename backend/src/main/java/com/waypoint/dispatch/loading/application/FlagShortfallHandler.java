package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.loading.application.LoadingMessages.Loaded;
import com.waypoint.dispatch.loading.contract.LoadingCommands;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingShortfall;
import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.loading.domain.LoadingSession;
import com.waypoint.dispatch.loading.domain.LoadingSession.Change;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Flag a missing, damaged or ill-fitting item before departure (Figma 03
 * Report an issue, R-LOD-02). The item is not loaded, the dispatcher and store
 * are told through loading.shortfall, and loading carries on (R-LOD-07).
 */
@Component
public class FlagShortfallHandler implements CommandHandler {
  private final JdbcLoadingRepository trips;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public FlagShortfallHandler(
      JdbcLoadingRepository trips, EventPublisher events, Metrics metrics, Clock clock) {
    this.trips = trips;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return LoadingCommands.SHORTFALL;
  }

  @Override
  public String action() {
    return LoadingCommands.SHORTFALL;
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
    CheckStatus kind = LoadingMessages.status(command, "kind");
    int missingUnits = LoadingMessages.requiredInt(command, "missingUnits");
    String reason = payload.requiredText("reason");
    Optional<UUID> photo = Optional.ofNullable(payload.optionalUuid("photoAttachmentId"));
    Instant now = clock.now();

    Loaded loaded = LoadingMessages.load(trips, tripId, expected);
    Change change = loaded.session().flag(actor.userId(), orderId, lineNo, kind, missingUnits);
    int affected = lineNo.isPresent() ? missingUnits : LoadingSession.unitsAffected(change.changed());

    long version = trips.updateSession(change.session().active(now), expected, now, Optional.empty());
    UUID shortfallId = UuidV7.generate(now, random);
    int planVersion = loaded.trip().planVersion();
    trips.insertShortfall(
        shortfallId, tripId, planVersion, orderId, lineNo, kind, affected, reason, photo,
        actor.userId(), actor.deviceId(), now);
    trips.appendChecks(
        tripId, planVersion, change.changed(), Optional.of(reason), Optional.of(shortfallId),
        actor.userId(), actor.deviceId(), command.commandId(), now, command.clientRecordedAt());
    events.publish(
        actor,
        new LoadingShortfall(
            shortfallId, tripId, orderId, loaded.trip().depotCode(), kind, affected, reason));
    metrics.increment("waypoint.loading.shortfall", "kind", JdbcLoadingRepository.code(kind));
    return LoadingMessages.result(tripId, version, change.session());
  }
}
