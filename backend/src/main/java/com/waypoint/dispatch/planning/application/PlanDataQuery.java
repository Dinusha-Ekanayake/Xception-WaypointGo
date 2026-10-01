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
 * <p>{@link #previewAssignments} and {@link #previewInterchange} throw
 * {@link UnsupportedOperationException} until step 6 of issue #9. An empty
 * answer would read as "nowhere fits" to Loading, which is a silent wrong
 * answer; a failure is visible (rule 9).
 */
@Component
public class PlanDataQuery implements PlanQuery {
  public static final String READ = "plan:Read";

  private final Database database;
  private final JdbcPlanRepository plans;
  private final ReferenceQuery reference;
  private final AuditLog audit;

  public PlanDataQuery(
      Database database, JdbcPlanRepository plans, ReferenceQuery reference, AuditLog audit) {
    this.database = database;
    this.plans = plans;
    this.reference = reference;
    this.audit = audit;
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
    throw new UnsupportedOperationException(
        "previewAssignments arrives with plan revision (issue #9, step 6)");
  }

  @Override
  public InterchangePreview previewInterchange(UUID tripId, String replacementVehicleId) {
    throw new UnsupportedOperationException(
        "previewInterchange arrives with plan revision (issue #9, step 6)");
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
