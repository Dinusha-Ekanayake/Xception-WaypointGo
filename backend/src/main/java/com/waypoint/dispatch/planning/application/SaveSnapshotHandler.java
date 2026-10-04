package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.contract.PlanViews.SnapshotKind;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.SnapshotRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A dispatcher saving the draft as it is. The draft is not changed, so no new
 * version is made; the draft must still be the version they saw, so what is
 * saved is what they were looking at.
 */
@Component
public class SaveSnapshotHandler implements CommandHandler {
  private final PlanningDrafts drafts;
  private final SnapshotRecords snapshots;
  private final Metrics metrics;
  private final Clock clock;

  public SaveSnapshotHandler(PlanningDrafts drafts, SnapshotRecords snapshots, Metrics metrics, Clock clock) {
    this.drafts = drafts;
    this.snapshots = snapshots;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.SAVE_SNAPSHOT;
  }

  @Override
  public String action() {
    return PlanCommands.SAVE_SNAPSHOT;
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
    Opened opened = drafts.open(payload.uuid("planId"), expected);
    SnapshotRow saved =
        snapshots.save(
            opened.row(), SnapshotKind.MANUAL, Optional.ofNullable(payload.text("label")), actor.userId(), clock.now());
    metrics.increment("waypoint.plan.snapshot_saved");
    return Map.of(
        "snapshotId", saved.snapshotId().toString(),
        "number", saved.number(),
        "label", saved.label());
  }
}
