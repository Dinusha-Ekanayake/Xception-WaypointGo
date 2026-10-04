package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.SnapshotRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A dispatcher returning the open draft to a saved plan. The saved plan is not
 * edited; a new draft takes its placements back and the engine places what
 * arrived since. If a placement no longer passes the rules the whole restore is
 * refused with the rule, and the draft stays as it was.
 */
@Component
public class RestoreSnapshotHandler implements CommandHandler {
  private final PlanningDrafts drafts;
  private final JdbcPlanRepository plans;
  private final PlanDataQuery view;
  private final GeneratePlanHandler generate;
  private final Clock clock;

  public RestoreSnapshotHandler(
      PlanningDrafts drafts, JdbcPlanRepository plans, PlanDataQuery view, GeneratePlanHandler generate, Clock clock) {
    this.drafts = drafts;
    this.plans = plans;
    this.view = view;
    this.generate = generate;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.RESTORE_SNAPSHOT;
  }

  @Override
  public String action() {
    return PlanCommands.RESTORE_SNAPSHOT;
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
    UUID snapshotId = payload.uuid("snapshotId");
    Opened opened = drafts.open(payload.uuid("planId"), expected);
    SnapshotRow saved =
        plans.findSnapshot(snapshotId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No saved plan " + snapshotId));
    if (!saved.depotCode().equals(opened.row().depotCode()) || !saved.serviceDate().equals(opened.row().serviceDate())) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "saved plan " + snapshotId + " is for " + saved.depotCode() + " on " + saved.serviceDate()
              + ", not for this plan's depot and day");
    }
    PlanView plan = view.readPlan(saved);
    Instant now = clock.now();
    PlanningRun run = generate.restore(actor, opened.row(), plan, now, command.commandId());
    return PlanningDrafts.body(run);
  }
}
