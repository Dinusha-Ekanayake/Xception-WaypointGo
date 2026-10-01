package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.ConstraintResultView;
import com.waypoint.dispatch.planning.contract.PlanViews.DeferralView;
import com.waypoint.dispatch.planning.contract.PlanViews.FuelView;
import com.waypoint.dispatch.planning.contract.PlanViews.InterchangePreview;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.StopView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import com.waypoint.dispatch.planning.application.PlanningDrafts.Opened;
import com.waypoint.dispatch.planning.application.PlanningProblems.Built;
import com.waypoint.dispatch.planning.application.PlanningRevisions.Place;
import com.waypoint.dispatch.planning.application.PlanningRevisions.Revision;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.ConstraintResult;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PlanningRun.Placement;
import com.waypoint.dispatch.planning.domain.PlanningRun.TripMove;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.DeferralRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.TripRow;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Reads plans, for other modules through {@link PlanQuery} and for the
 * dispatcher through the web layer.
 *
 * <p>Every read runs as {@code waypoint_planning} in a read-only transaction of
 * its own ({@link Database#readAs}), so row-level security narrows it to the
 * actor's depots in SQL (rule 7). A contract caller reads as the actor of the
 * unit of work it is inside; with none, it sees nothing.
 *
 * <p>A single plan outside scope is indistinguishable from one that does not
 * exist: {@code 404}. A depot-day asked for outside scope is {@code 403} plus an
 * audit row, never an empty answer, because "no plan" and "not yours" must not
 * look alike (AGENTS.md, Security).
 *
 * <p>The previews are the commands they preview, run read-only: the same
 * registry, the same revision under the versions in force now, nothing stored.
 * An order in no open draft, or a trip in no live plan, is {@code 404}, never an
 * empty answer that would read as "nowhere fits" (rule 9).
 */
@Component
public class PlanDataQuery implements PlanQuery {
  public static final String READ = "plan:Read";

  private final Database database;
  private final JdbcPlanRepository plans;
  private final ReferenceQuery reference;
  private final AuditLog audit;
  private final PlanningDrafts drafts;
  private final PlanningRevisions revisions;
  private final ConstraintRegistry registry;

  PlanDataQuery(
      Database database,
      JdbcPlanRepository plans,
      ReferenceQuery reference,
      AuditLog audit,
      PlanningDrafts drafts,
      PlanningRevisions revisions,
      ConstraintRegistry registry) {
    this.database = database;
    this.plans = plans;
    this.reference = reference;
    this.audit = audit;
    this.drafts = drafts;
    this.revisions = revisions;
    this.registry = registry;
  }

  // ---- contract: as the ambient actor --------------------------------------

  @Override
  public Optional<PlanView> publishedPlan(String depotCode, LocalDate serviceDate) {
    return read(ambient(), () -> plans.published(depotCode, serviceDate).map(this::assemble));
  }

  @Override
  public Optional<PlanView> draft(UUID planId) {
    return read(
        ambient(),
        () -> plans.findRun(planId).filter(r -> r.status() == PlanStatus.DRAFT).map(this::assemble));
  }

  @Override
  public List<AllocationView> previewAssignments(UUID orderId) {
    return read(ambient(), () -> assignments(orderId));
  }

  @Override
  public InterchangePreview previewInterchange(UUID tripId, String replacementVehicleId) {
    return read(ambient(), () -> interchange(tripId, replacementVehicleId));
  }

  public List<AllocationView> previewAssignments(Actor actor, UUID orderId) {
    return read(actor.userId(), () -> assignments(orderId));
  }

  public InterchangePreview previewInterchange(Actor actor, UUID tripId, String replacementVehicleId) {
    return read(actor.userId(), () -> interchange(tripId, replacementVehicleId));
  }

  @Override
  public List<DeferralView> deferralsFor(String depotCode, LocalDate serviceDate) {
    return read(ambient(), () -> deferrals(depotCode, serviceDate));
  }

  @Override
  public Optional<FuelView> fuelRemaining(String vehicleId, LocalDate anyDayOfWeek) {
    return reference
        .vehicle(vehicleId, null)
        .map(v -> fuel(v, read(ambient(), () -> plans.fuelUsed(vehicleId, anyDayOfWeek)), anyDayOfWeek));
  }

  // ---- web: as the authenticated actor -------------------------------------

  public PlanView publishedPlan(Actor actor, String depotCode, LocalDate serviceDate) {
    requireDepot(actor, depotCode);
    return read(actor.userId(), () -> plans.published(depotCode, serviceDate).map(this::assemble))
        .orElseThrow(
            () ->
                new DomainException(
                    ErrorCode.NOT_FOUND,
                    "No published plan for " + depotCode + " on " + serviceDate));
  }

  /** Any run by id, in any status, so the dispatcher can read a superseded version too. */
  public PlanView plan(Actor actor, UUID planId) {
    return read(actor.userId(), () -> plans.findRun(planId).map(this::assemble))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId));
  }

  public List<DeferralView> deferralsFor(Actor actor, String depotCode, LocalDate serviceDate) {
    requireDepot(actor, depotCode);
    return read(actor.userId(), () -> deferrals(depotCode, serviceDate));
  }

  public FuelView fuelRemaining(Actor actor, String vehicleId, LocalDate anyDayOfWeek) {
    VehicleView vehicle =
        reference
            .vehicle(vehicleId, null)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No vehicle " + vehicleId));
    requireDepot(actor, vehicle.depotCode());
    return fuel(vehicle, read(actor.userId(), () -> plans.fuelUsed(vehicleId, anyDayOfWeek)), anyDayOfWeek);
  }

  // ---- internals -----------------------------------------------------------

  private UUID ambient() {
    return database.ambientActor().orElse(null);
  }

  private <T> T read(UUID actorId, Supplier<T> work) {
    return database.readAs(ModuleRole.PLANNING, actorId, work);
  }

  /**
   * Policy allowed {@code plan:Read}; this is the scope half of "policy AND
   * scope". The check reads in its own transaction and the denial is audited
   * after it ends, because the audit write cannot join a read-only transaction.
   */
  private void requireDepot(Actor actor, String depotCode) {
    String resource = "wpt:plan:depot:" + depotCode;
    boolean inScope =
        read(
            actor.userId(),
            () ->
                Boolean.TRUE.equals(
                    database.queryOne("SELECT app.actor_has_depot(?) AS ok", depotCode).get("ok")));
    if (!inScope) {
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }

  /** Every place the order could take in its open draft, feasible first, each with its checks. */
  private List<AllocationView> assignments(UUID orderId) {
    RunRow row =
        plans.openDraftsWithOrder(orderId).stream().findFirst()
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "order " + orderId + " is in no open draft"));
    Opened opened = drafts.rebuild(row);
    PlanOrder order = opened.built().orders().get(orderId);
    if (order == null) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED, "order " + orderId + " is no longer in the demand", List.of("PLN-07"));
    }
    Map<String, UUID> tripIds = new java.util.HashMap<>();
    plans.trips(row.planId()).forEach(t -> tripIds.put(t.vehicleId() + "/" + t.tripNumber(), t.tripId()));
    return drafts.run(opened)
        .options(order, new java.util.ArrayList<>(opened.built().fleet().values()), registry,
            opened.built().problem().context())
        .stream()
        .sorted(java.util.Comparator.comparing((Placement p) -> !p.feasible()))
        .map(p -> toView(orderId, p, tripIds))
        .toList();
  }

  /** Whether {@code replacement} could take the trip whole, judged as the interchange itself would be. */
  private InterchangePreview interchange(UUID tripId, String replacementVehicleId) {
    RunRow row =
        plans.runsWithTrip(tripId).stream().findFirst()
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No live plan carries trip " + tripId));
    PlanningRun run;
    Built built;
    if (row.status() == PlanStatus.PUBLISHED) {
      Revision revision =
          revisions.revise(row, "interchange preview", Actor.SYSTEM_ID, UUID.randomUUID(), row.planVersion() + 1);
      run = revision.run();
      built = revision.built();
    } else {
      Opened opened = drafts.rebuild(row);
      run = drafts.run(opened);
      built = opened.built();
    }
    Place place = revisions.locate(row.planId(), tripId, run);
    FleetVehicle replacement = built.fleet().get(replacementVehicleId);
    if (replacement == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, replacementVehicleId + " is not a vehicle of depot " + row.depotCode());
    }
    TripMove move = run.moveTrip(place.vehicleId(), place.tripNumber(), replacement, registry, built.problem().context());
    return new InterchangePreview(
        tripId, place.vehicleId(), replacementVehicleId, move.feasible(),
        move.checks().stream().map(PlanDataQuery::toView).toList());
  }

  private static AllocationView toView(UUID orderId, Placement p, Map<String, UUID> tripIds) {
    Optional<ConstraintResult> failed = ConstraintRegistry.firstFailure(p.checks());
    String where = (p.joins() ? "join " : "new trip ") + p.vehicleId() + " trip " + p.tripNumber();
    return new AllocationView(
        orderId,
        p.feasible() ? AllocationDecision.SERVED : AllocationDecision.DEFERRED,
        p.joins() ? Optional.ofNullable(tripIds.get(p.vehicleId() + "/" + p.tripNumber())) : Optional.empty(),
        failed.map(ConstraintResult::ruleId),
        failed.map(f -> where + ": " + f.reason()).orElse(where),
        p.checks().stream().map(PlanDataQuery::toView).toList());
  }

  private static ConstraintResultView toView(ConstraintResult c) {
    return new ConstraintResultView(c.ruleId(), c.passed(), c.reason(), c.slack());
  }

  /**
   * The deferrals of the plan that decides the day: the published one, or
   * while nothing is published, the newest draft. Empty when there is neither.
   */
  private List<DeferralView> deferrals(String depotCode, LocalDate serviceDate) {
    Optional<RunRow> deciding =
        plans.published(depotCode, serviceDate).or(() -> plans.latestDraft(depotCode, serviceDate));
    return deciding.map(r -> plans.deferrals(r.planId())).orElse(List.of()).stream()
        .map(PlanDataQuery::toView)
        .toList();
  }

  private static FuelView fuel(VehicleView vehicle, BigDecimal used, LocalDate anyDay) {
    BigDecimal quota = vehicle.weeklyFuelQuotaL();
    return new FuelView(
        vehicle.vehicleId(), JdbcPlanRepository.weekStarting(anyDay), quota, used, quota.subtract(used));
  }

  /** Called inside a read, so the children come from the same snapshot as the run. */
  private PlanView assemble(RunRow run) {
    List<AllocationRow> allocations = plans.allocations(run.planId());
    Map<UUID, List<StopView>> stops =
        allocations.stream()
            .filter(a -> a.decision() == AllocationDecision.SERVED)
            .collect(
                Collectors.groupingBy(
                    a -> a.tripId().orElseThrow(),
                    Collectors.mapping(PlanDataQuery::toStop, Collectors.toList())));
    List<TripView> trips =
        plans.trips(run.planId()).stream()
            .map(t -> toView(t, stops.getOrDefault(t.tripId(), List.of())))
            .toList();
    return new PlanView(
        run.planId(),
        run.depotCode(),
        run.serviceDate(),
        run.planVersion(),
        run.status(),
        run.referenceVersionId(),
        run.ruleSetId(),
        run.priorityPolicyVersionId(),
        run.supersedes(),
        run.publishedAt(),
        run.plannedWithoutPredictor(),
        trips,
        allocations.stream().map(PlanDataQuery::toView).toList(),
        run.rowVersion());
  }

  private static TripView toView(TripRow t, List<StopView> stops) {
    return new TripView(
        t.tripId(),
        t.vehicleId(),
        t.tripNumber(),
        t.brandCode(),
        t.districtName(),
        t.temperature(),
        t.weightKg(),
        t.volumeM3(),
        t.plannedMinutes(),
        t.plannedDeparture(),
        stops.stream().sorted(java.util.Comparator.comparingInt(StopView::sequence)).toList());
  }

  private static StopView toStop(AllocationRow a) {
    return new StopView(
        a.stopSequence().orElseThrow(),
        a.orderId(),
        a.outletId(),
        a.plannedArrival().orElseThrow(),
        a.windowOpen().orElse(null),
        a.windowClose().orElse(null),
        a.serviceMinutes());
  }

  private static AllocationView toView(AllocationRow a) {
    return new AllocationView(
        a.orderId(),
        a.decision(),
        a.tripId(),
        a.bindingRule(),
        a.reason(),
        a.checks().stream()
            .map(c -> new ConstraintResultView(c.ruleId(), c.passed(), c.reason(), c.slack()))
            .toList());
  }

  private static DeferralView toView(DeferralRow d) {
    return new DeferralView(d.orderId(), d.outletId(), d.serviceDate(), d.ruleId(), d.reason(), d.skipCount());
  }
}
