package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningProblems.Built;
import com.waypoint.dispatch.planning.contract.PlanCommands;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.SnapshotKind;
import com.waypoint.dispatch.planning.domain.AllocationEngine;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PlanningRun.Stamps;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcGenerationJobs;
import com.waypoint.dispatch.planning.infrastructure.JdbcGenerationJobs.JobRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
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
  private final SnapshotRecords snapshots;
  private final PlanDataQuery view;
  private final JdbcGenerationJobs jobs;
  private final GenerationSignal signal;

  public GeneratePlanHandler(
      Database database,
      JdbcPlanRepository plans,
      PlanningProblems problems,
      PlanningDrafts drafts,
      AllocationEngine engine,
      ReferenceQuery reference,
      Metrics metrics,
      Clock clock,
      SnapshotRecords snapshots,
      PlanDataQuery view,
      JdbcGenerationJobs jobs,
      GenerationSignal signal) {
    this.database = database;
    this.plans = plans;
    this.problems = problems;
    this.drafts = drafts;
    this.engine = engine;
    this.reference = reference;
    this.metrics = metrics;
    this.clock = clock;
    this.snapshots = snapshots;
    this.view = view;
    this.jobs = jobs;
    this.signal = signal;
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
    boolean keep = payload.flag("keepDecisions", false);
    refuseClosedDay(depot, serviceDate);
    // The engine runs in the queue, not in this transaction (R-PLN-41): a second Generate finds the same job.
    JobRow job = jobs.enqueue(drafts.newId(now), depot, serviceDate, keep, actor.userId(), command.commandId(), now);
    database.afterCommit(signal::wake);
    metrics.increment("waypoint.plan.generation_queued");
    return jobBody(job);
  }

  /** What a Generate answers: the job, which the screen follows until the draft is written. */
  static Map<String, Object> jobBody(JobRow job) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("jobId", job.jobId().toString());
    body.put("status", job.status().toUpperCase(java.util.Locale.ROOT));
    body.put("depotCode", job.depotCode());
    body.put("serviceDate", job.serviceDate().toString());
    return body;
  }

  /** A day that is not operating, or already published, is refused at once rather than queued. */
  public void refuseClosedDay(String depot, LocalDate serviceDate) {
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
  }

  // ---- the queued generation, in three steps (R-PLN-41) ---------------------------

  /** What a job needs from the database, read in one short transaction. */
  public record JobInput(Built built, Stamps stamps, Optional<RunRow> open, KeptDecisions kept) {
    Problem problem() {
      return kept.isEmpty() ? built.problem() : built.problem().keeping(kept.pins(), kept.held());
    }
  }

  /** Step 1, inside a transaction as the requester: the demand, fleet and rules in force, and what to keep. */
  public JobInput prepareJob(String depot, LocalDate serviceDate, boolean keepDecisions) {
    refuseClosedDay(depot, serviceDate);
    Prepared prepared = prepare(depot, serviceDate);
    Optional<RunRow> open = plans.latestDraft(depot, serviceDate);
    KeptDecisions kept =
        keepDecisions && open.isPresent()
            ? KeptDecisions.ofDraft(plans.allocations(open.get().planId()), plans.trips(open.get().planId()))
                .within(prepared.built().orders().keySet())
            : KeptDecisions.NONE;
    return new JobInput(prepared.built(), prepared.stamps(), open, kept);
  }

  /** Step 2, with no transaction open: the engine. Pure, so a retry of step 3 never repeats it. */
  public AllocationResult allocate(JobInput input) {
    long started = System.nanoTime();
    AllocationResult result = engine.allocate(input.problem());
    metrics.record("waypoint.plan.engine_ms", (System.nanoTime() - started) / 1_000_000L, "engine", engine.name());
    return result;
  }

  /**
   * Step 3, inside a transaction as the requester: the draft, but only if the
   * day is as the engine saw it. Empty when the demand changed meanwhile, so the
   * job runs again on the new demand rather than writing a plan for the old one.
   */
  public Optional<PlanningRun> persistJob(
      Actor actor, JobInput input, AllocationResult result, Instant now, UUID commandId) {
    refuseClosedDay(input.built().problem().depotCode(), input.built().problem().serviceDate());
    Prepared current = prepare(input.built().problem().depotCode(), input.built().problem().serviceDate());
    if (!current.built().fingerprint().equals(input.built().fingerprint())) {
      metrics.increment("waypoint.plan.generation_demand_moved");
      return Optional.empty();
    }
    Optional<RunRow> open = plans.latestDraft(input.built().problem().depotCode(), input.built().problem().serviceDate());
    return Optional.of(persist(actor, input.built(), input.stamps(), open, input.kept(), now, commandId, result));
  }

  /**
   * Generates and stores a draft. The caller has decided the actor may; the
   * {@code orders.closed} consumer calls this as the system.
   */
  public PlanningRun generate(Actor actor, String depot, LocalDate serviceDate, Instant now, UUID commandId) {
    return generate(actor, depot, serviceDate, now, commandId, false);
  }

  /**
   * @param keepDecisions put back what a dispatcher placed, locked or kept
   *     deferred in the open draft before the engine places the rest (R-PLN-36);
   *     a decision that no longer holds refuses the whole generate and names it
   */
  public PlanningRun generate(
      Actor actor, String depot, LocalDate serviceDate, Instant now, UUID commandId, boolean keepDecisions) {
    refuseClosedDay(depot, serviceDate);

    Prepared prepared = prepare(depot, serviceDate);
    Optional<RunRow> open = plans.latestDraft(depot, serviceDate);
    KeptDecisions kept =
        keepDecisions && open.isPresent()
            ? KeptDecisions.ofDraft(plans.allocations(open.get().planId()), plans.trips(open.get().planId()))
                .within(prepared.built().orders().keySet())
            : KeptDecisions.NONE;
    return produce(actor, prepared.built(), prepared.stamps(), open, kept, now, commandId);
  }

  /**
   * Returns the open draft to a saved plan: the demand is read as it is now,
   * every placement the snapshot made goes back (the dispatcher's own decisions
   * under their name), and the engine places whatever arrived since.
   */
  PlanningRun restore(Actor actor, RunRow open, PlanView saved, Instant now, UUID commandId) {
    Prepared prepared = prepare(open.depotCode(), open.serviceDate());
    KeptDecisions kept = KeptDecisions.ofSnapshot(saved, actor.userId()).within(prepared.built().orders().keySet());
    metrics.increment("waypoint.plan.restored");
    return produce(actor, prepared.built(), prepared.stamps(), Optional.of(open), kept, now, commandId);
  }

  private record Prepared(Built built, Stamps stamps) {}

  /** The demand, fleet, rule set and priority policy in force for a depot and day. */
  private Prepared prepare(String depot, LocalDate serviceDate) {
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
    return new Prepared(built, new Stamps(referenceVersion, rules.id(), policy.id()));
  }

  /**
   * Runs the engine over {@code built} with {@code kept} decisions to honour
   * and stores the result as the day's open draft. The previous draft, if any,
   * is saved first, then cancelled; the engine runs before anything is written,
   * so a decision that cannot be kept leaves the previous draft as it was.
   */
  PlanningRun produce(
      Actor actor,
      Built built,
      Stamps stamps,
      Optional<RunRow> open,
      KeptDecisions kept,
      Instant now,
      UUID commandId) {
    AllocationResult result = allocate(new JobInput(built, stamps, open, kept));
    return persist(actor, built, stamps, open, kept, now, commandId, result);
  }

  /** Stores an engine result as the day's open draft: the previous draft saved, then cancelled. */
  private PlanningRun persist(
      Actor actor,
      Built built,
      Stamps stamps,
      Optional<RunRow> open,
      KeptDecisions kept,
      Instant now,
      UUID commandId,
      AllocationResult result) {
    String depot = built.problem().depotCode();
    LocalDate serviceDate = built.problem().serviceDate();

    open.ifPresent(previous -> snapshots.save(previous, SnapshotKind.REGENERATED, Optional.empty(), actor.userId(), now));
    drafts.cancelOpen(depot, serviceDate, now);
    PlanningRun run =
        PlanningRun.draft(
                drafts.newId(now),
                depot,
                serviceDate,
                drafts.nextVersion(depot, serviceDate),
                stamps,
                Optional.empty(),
                built.fingerprint(),
                result,
                Actor.SYSTEM_ID)
            .withCarried(kept.marks(), kept.heldDecisions());
    drafts.write(run, built, actor.userId(), now, commandId);
    RunRow saved = plans.findRun(run.planId()).orElseThrow();
    snapshots.save(saved, SnapshotKind.AUTO, Optional.empty(), actor.userId(), now);
    // The cost stage replaced the rules plan: keep that plan beside the draft, to compare and choose.
    if (result.alternative().isPresent()) {
      PlanningRun rules =
          PlanningRun.draft(run.planId(), depot, serviceDate, run.planVersion(), stamps, Optional.empty(),
              built.fingerprint(), result.alternative().get(), Actor.SYSTEM_ID);
      PlanRecords.Rows rows = PlanRecords.of(rules, built, actor.userId(), now, () -> drafts.newId(now));
      snapshots.saveView(view.viewOf(rows), saved, SnapshotKind.RULES, actor.userId(), now);
      metrics.increment("waypoint.plan.rules_alternative");
    }

    metrics.increment("waypoint.plan.generated");
    if (run.partial()) {
      metrics.increment("waypoint.plan.partial");
    }
    return run;
  }
}
