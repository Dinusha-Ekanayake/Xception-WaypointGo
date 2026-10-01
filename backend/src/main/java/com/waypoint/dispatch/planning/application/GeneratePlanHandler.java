package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningProblems.Built;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PlanningRun.Stamps;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A fresh draft for a depot and day: demand from Ordering, the fleet and
 * outlets at the current reference version, the rule set and priority policy in
 * force, through the validated engine.
 *
 * <p>An open draft for the same day is cancelled, so regenerating is how a
 * stale draft is refreshed; a dispatcher still editing it is refused on their
 * next command with the difference (PLN-06). A day that already has a published
 * plan is revised, not regenerated (R-PLN-28).
 */
@Component
public class GeneratePlanHandler implements CommandHandler {
  private final Database database;
  private final JdbcPlanRepository plans;
  private final PlanningProblems problems;
  private final PlanningDrafts drafts;
  private final AllocationEngine engine;
  private final ReferenceQuery reference;
  private final Metrics metrics;
  private final Clock clock;

  public GeneratePlanHandler(
      Database database,
      JdbcPlanRepository plans,
      PlanningProblems problems,
      PlanningDrafts drafts,
      AllocationEngine engine,
      ReferenceQuery reference,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.plans = plans;
    this.problems = problems;
    this.drafts = drafts;
    this.engine = engine;
    this.reference = reference;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlanCommands.GENERATE;
  }

  @Override
  public String action() {
    return PlanCommands.GENERATE;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.PLANNING;
  }

  @Override
  public String resource(Command command) {
    String depot = CommandPayload.of(command).text("depotCode");
    return depot == null ? null : "wpt:plan:depot:" + depot;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    String depot = payload.requiredText("depotCode");
    LocalDate serviceDate = payload.date("serviceDate");
    Instant now = clock.now();

    Map<String, Object> scope =
        database.queryOne("SELECT app.actor_is_system() OR app.actor_has_depot(?) AS ok", depot);
    if (!Boolean.TRUE.equals(scope.get("ok"))) {
      throw new DomainException(ErrorCode.FORBIDDEN, "Depot " + depot + " is outside the actor's scope");
    }
    return PlanningDrafts.body(generate(actor, depot, serviceDate, now, command.commandId()));
  }

  /**
   * Generates and stores a draft. The caller has decided the actor may; the
   * {@code orders.closed} consumer calls this as the system.
   */
  public PlanningRun generate(Actor actor, String depot, LocalDate serviceDate, Instant now, UUID commandId) {
    if (!reference.isOperating(serviceDate)) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED, serviceDate + " is not an operating day", List.of("PLN-13"));
    }
    if (plans.published(depot, serviceDate).isPresent()) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "a plan is already published for " + depot + " on " + serviceDate + "; revise it instead",
          List.of("R-PLN-28"));
    }

    UUID referenceVersion =
        reference
            .currentVersionId()
            .orElseThrow(
                () -> new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "No reference version is loaded"));
    RuleSet rules =
        plans
            .effectiveRuleSet(serviceDate)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.CONSTRAINT_VIOLATED,
                        "no rule set is in force on " + serviceDate + "; planning refuses rather than guess",
                        List.of("POL-10")));
    PriorityPolicy policy =
        plans
            .effectivePolicy(depot, serviceDate)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.CONSTRAINT_VIOLATED,
                        "no deferral priority policy is in force for " + depot + " on " + serviceDate,
                        List.of("POL-10", "R-PLN-21")));

    Built built = problems.build(depot, serviceDate, referenceVersion, rules, policy, Optional.empty());
    long started = System.nanoTime();
    AllocationResult result = engine.allocate(built.problem());
    metrics.record("waypoint.plan.engine_ms", (System.nanoTime() - started) / 1_000_000L, "engine", engine.name());

    drafts.cancelOpen(depot, serviceDate, now);
    PlanningRun run =
        PlanningRun.draft(
            drafts.newId(now),
            depot,
            serviceDate,
            drafts.nextVersion(depot, serviceDate),
            new Stamps(referenceVersion, rules.id(), policy.id()),
            Optional.empty(),
            built.fingerprint(),
            result,
            Actor.SYSTEM_ID);
    drafts.write(run, built, actor.userId(), now, commandId);

    metrics.increment("waypoint.plan.generated");
    if (run.partial()) {
      metrics.increment("waypoint.plan.partial");
    }
    return run;
  }
}
