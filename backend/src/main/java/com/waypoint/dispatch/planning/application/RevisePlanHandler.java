package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.domain.PublishedEditWindow;
import com.waypoint.dispatch.planning.application.PlanningRevisions.Revision;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A new draft from the published plan, under the versions in force now
 * (R-PLN-28). The published plan stays exactly as it is until the revision is
 * published and supersedes it. Trips that do not change keep their ids.
 */
@Component
public class RevisePlanHandler implements CommandHandler {
  private final PlanningRevisions revisions;
  private final PlanningDrafts drafts;
  private final Metrics metrics;
  private final Clock clock;

  RevisePlanHandler(PlanningRevisions revisions, PlanningDrafts drafts, Metrics metrics, Clock clock) {
    this.revisions = revisions;
    this.drafts = drafts;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.REVISE;
  }

  @Override
  public String action() {
    return PlanCommands.REVISE;
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
    String reason = PlanningDrafts.reason(payload);
    Instant now = clock.now();

    RunRow published = revisions.published(payload.uuid("planId"), expected);
    PublishedEditWindow.requireOpen(published.serviceDate(), now);
    Revision revision =
        revisions.revise(
            published, reason, actor.userId(), drafts.newId(now),
            drafts.nextVersion(published.depotCode(), published.serviceDate()));
    revisions.store(published, revision.run(), revision.built(), actor.userId(), now, command.commandId());
    metrics.increment("waypoint.plan.revision_drafted", "by", "dispatcher");
    return PlanningDrafts.body(revision.run());
  }
}
