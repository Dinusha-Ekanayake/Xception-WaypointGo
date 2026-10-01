package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.application.PlanningRevisions.Place;
import com.waypoint.dispatch.planning.application.PlanningRevisions.Revision;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PlanningRun.Replanned;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Moves one trip whole to another vehicle, the requested one first, and only
 * that trip (PLN-04, R-LOD-06). With no vehicle able to take it, the trip
 * defers as a unit and its orders keep their identity (R-LOD-09, LOD-03).
 *
 * <p>On a draft the result is the draft's next version. On the published plan
 * it is a revision draft for the dispatcher to publish; only the interchange
 * consumer publishes on its own, and only when nothing else changed.
 */
@Component
public class ReplanTripHandler implements CommandHandler {
  private final JdbcPlanRepository plans;
  private final PlanningDrafts drafts;
  private final PlanningRevisions revisions;
  private final Metrics metrics;
  private final Clock clock;

  ReplanTripHandler(
      JdbcPlanRepository plans, PlanningDrafts drafts, PlanningRevisions revisions, Metrics metrics, Clock clock) {
    this.plans = plans;
    this.drafts = drafts;
    this.revisions = revisions;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.REPLAN;
  }

  @Override
  public String action() {
    return PlanCommands.REPLAN;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.PLANNING;
  }

  @Override
  public String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("planId");
    return id == null ? null : "wpt:plan:plan:" + id;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    long expected = PlanningDrafts.expectedVersion(command);
    CommandPayload payload = CommandPayload.of(command);
    UUID planId = payload.uuid("planId");
    UUID tripId = payload.uuid("tripId");
    Optional<String> replacement = Optional.ofNullable(payload.text("replacementVehicleId")).filter(v -> !v.isBlank());
    String reason = PlanningDrafts.reason(payload);
    Instant now = clock.now();

    RunRow row = plans.findRun(planId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId));
    Replanned replanned;
    if (row.status() == PlanStatus.PUBLISHED) {
      RunRow published = revisions.published(planId, expected);
      Revision revision =
          revisions.revise(
              published, "replan trip " + tripId + ": " + reason, actor.userId(), drafts.newId(now),
              drafts.nextVersion(published.depotCode(), published.serviceDate()));
      Place place = revisions.locate(planId, tripId, revision.run());
      replanned = revisions.replanTrip(revision, place, replacement, reason, actor.userId());
      revisions.store(published, replanned.run(), revision.built(), actor.userId(), now, command.commandId());
    } else {
      Opened opened = drafts.open(planId, expected);
      PlanningDrafts.requireSameDemand(opened);
      PlanningRun current = drafts.run(opened);
      Place place = revisions.locate(planId, tripId, current);
      replanned =
          revisions.replanTrip(
              current, opened.built(), place, replacement, reason, actor.userId(), drafts.newId(now),
              drafts.nextVersion(row.depotCode(), row.serviceDate()));
      drafts.replace(opened, replanned.run(), actor.userId(), now, command.commandId());
    }

    metrics.increment("waypoint.plan.replanned", "outcome", replanned.movedTo().isPresent() ? "moved" : "deferred");
    Map<String, Object> body = new HashMap<>(PlanningDrafts.body(replanned.run()));
    body.put("tripId", tripId.toString());
    replanned.movedTo().ifPresent(v -> body.put("movedTo", v));
    body.put("deferredAsUnit", replanned.movedTo().isEmpty());
    return body;
  }
}
