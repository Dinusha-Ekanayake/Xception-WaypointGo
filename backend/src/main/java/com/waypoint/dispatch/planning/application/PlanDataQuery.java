package com.waypoint.dispatch.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.intelligence.contract.PredictionQuery;
import com.waypoint.dispatch.intelligence.contract.PredictionViews.PlanScoringView;
import org.springframework.beans.factory.ObjectProvider;
import com.waypoint.dispatch.planning.contract.PlanQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationLineView;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationPageView;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationSource;
import com.waypoint.dispatch.planning.contract.PlanViews.ComparisonView;
import com.waypoint.dispatch.planning.contract.PlanViews.SnapshotDetailView;
import com.waypoint.dispatch.planning.contract.PlanViews.SnapshotView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripPreview;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanSummaryView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripSummaryView;
import com.waypoint.dispatch.planning.contract.PlanViews.ImprovementView;
import com.waypoint.dispatch.planning.contract.PlanViews.ConstraintResultView;
import com.waypoint.dispatch.planning.contract.PlanViews.DeferralView;
import com.waypoint.dispatch.planning.contract.PlanViews.FuelView;
import com.waypoint.dispatch.planning.contract.PlanViews.InterchangePreview;
import com.waypoint.dispatch.planning.contract.PlanViews.PlacementView;
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
import com.waypoint.dispatch.planning.domain.PlanContext;
import com.waypoint.dispatch.planning.domain.PlanDiff;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PlanningRun.Placement;
import com.waypoint.dispatch.planning.domain.PlanningRun.Proposed;
import com.waypoint.dispatch.planning.domain.PlanningRun.TripMove;
import com.waypoint.dispatch.planning.domain.TripTimeline;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.DeferralRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.SnapshotRow;
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
  private final ObjectProvider<PredictionQuery> predictions;
  private final ObjectMapper json;

  PlanDataQuery(
      Database database,
      JdbcPlanRepository plans,
      ReferenceQuery reference,
      AuditLog audit,
      PlanningDrafts drafts,
      PlanningRevisions revisions,
      ConstraintRegistry registry,
      ObjectProvider<PredictionQuery> predictions,
      ObjectMapper json) {
    this.database = database;
    this.plans = plans;
    this.reference = reference;
    this.audit = audit;
    this.drafts = drafts;
    this.revisions = revisions;
    this.registry = registry;
    this.predictions = predictions;
    this.json = json;
  }

  // ---- contract: as the ambient actor --------------------------------------

  @Override
  public Optional<PlanView> publishedPlan(String depotCode, LocalDate serviceDate) {
    return read(ambient(), () -> plans.published(depotCode, serviceDate).map(this::assemble));
  }

  @Override
  public Optional<PlanView> plan(UUID planId) {
    return read(ambient(), () -> plans.findRun(planId).map(this::assemble));
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

  /** The same places as {@link #previewAssignments}, each naming the vehicle and trip an override sends. */
  public List<PlacementView> previewPlacements(Actor actor, UUID orderId) {
    return read(actor.userId(), () -> options(orderId, PlanDataQuery::toPlacement));
  }

  public InterchangePreview previewInterchange(Actor actor, UUID tripId, String replacementVehicleId) {
    return read(actor.userId(), () -> interchange(tripId, replacementVehicleId));
  }

  /** The saved plans of a depot and day, newest first. */
  public List<SnapshotView> snapshots(Actor actor, String depotCode, LocalDate serviceDate) {
    requireDepot(actor, depotCode);
    return read(
        actor.userId(),
        () -> plans.snapshots(depotCode, serviceDate).stream().map(SnapshotRecords::view).toList());
  }

  /** One saved plan with the plan itself, read only. Outside the actor's depots it is not found. */
  public SnapshotDetailView snapshot(Actor actor, UUID snapshotId) {
    return read(
            actor.userId(),
            () -> plans.findSnapshot(snapshotId).map(row -> new SnapshotDetailView(SnapshotRecords.view(row), readPlan(row))))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No saved plan " + snapshotId));
  }

  /**
   * Two plans of one depot and day side by side. Each id names a saved plan or
   * a plan version; both are read under the actor, so one outside their depots
   * is not found.
   */
  public ComparisonView compare(Actor actor, UUID a, UUID b) {
    return read(
        actor.userId(),
        () -> {
          Named first = named(a);
          Named second = named(b);
          if (!first.plan().depotCode().equals(second.plan().depotCode())
              || !first.plan().serviceDate().equals(second.plan().serviceDate())) {
            throw new DomainException(
                ErrorCode.VALIDATION_FAILED, "plans of different depots or days cannot be compared");
          }
          return PlanDiff.compare(first.label(), first.plan(), second.label(), second.plan());
        });
  }

  private record Named(String label, PlanView plan) {}

  private Named named(UUID id) {
    Optional<SnapshotRow> saved = plans.findSnapshot(id);
    if (saved.isPresent()) {
      return new Named(saved.get().label(), readPlan(saved.get()));
    }
    RunRow run =
        plans.findRun(id).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan or saved plan " + id));
    String label =
        switch (run.status()) {
          case PUBLISHED -> "Published version " + run.planVersion();
          case DRAFT -> "Working draft (version " + run.planVersion() + ")";
          default -> "Version " + run.planVersion();
        };
    return new Named(label, assemble(run));
  }

  PlanView readPlan(SnapshotRow row) {
    try {
      return json.readValue(row.payload(), PlanView.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("saved plan " + row.snapshotId() + " cannot be read", e);
    }
  }

  /** The trip a swap would leave, and every rule's verdict, before the dispatcher commits to it. */
  public TripPreview previewSwap(Actor actor, UUID outOrderId, UUID inOrderId) {
    return read(actor.userId(), () -> swapPreview(outOrderId, inOrderId));
  }

  /** The trip with its stops in the order given, timed, and every rule's verdict on it. */
  public TripPreview previewSequence(Actor actor, UUID tripId, List<UUID> orderIds) {
    return read(actor.userId(), () -> sequencePreview(tripId, orderIds));
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

  /**
   * The open draft for a depot and day. Every edit replaces the draft with its
   * next version under a new id, so a screen asks for it here rather than
   * holding an id that the next edit, or another dispatcher, makes stale.
   */
  public PlanView workingDraft(Actor actor, String depotCode, LocalDate serviceDate) {
    requireDepot(actor, depotCode);
    return read(actor.userId(), () -> plans.latestDraft(depotCode, serviceDate).map(this::assemble))
        .orElseThrow(
            () ->
                new DomainException(
                    ErrorCode.NOT_FOUND, "No open draft for " + depotCode + " on " + serviceDate));
  }

  /** The largest allocation page; a client asks again with the cursor for more. */
  public static final int MAX_ALLOCATION_PAGE = 100;

  /**
   * The published plan without its allocations (issue #177), for a client that
   * cannot hold a whole plan. The same depot scope and the same 404 as
   * {@link #publishedPlan}.
   */
  public PlanSummaryView publishedSummary(Actor actor, String depotCode, LocalDate serviceDate) {
    requireDepot(actor, depotCode);
    return read(actor.userId(), () -> plans.published(depotCode, serviceDate).map(this::summarise))
        .orElseThrow(
            () ->
                new DomainException(
                    ErrorCode.NOT_FOUND,
                    "No published plan for " + depotCode + " on " + serviceDate));
  }

  public PlanSummaryView draftSummary(Actor actor, String depotCode, LocalDate serviceDate) {
    requireDepot(actor, depotCode);
    return read(actor.userId(), () -> plans.latestDraft(depotCode, serviceDate).map(this::summarise))
        .orElseThrow(
            () ->
                new DomainException(
                    ErrorCode.NOT_FOUND, "No open draft for " + depotCode + " on " + serviceDate));
  }

  /**
   * One keyset page of a plan's allocations, each with its constraint results.
   * Read under the actor like {@link #plan}, so row-level security hides a plan
   * outside their depots as not found.
   */
  public AllocationPageView allocationPage(Actor actor, UUID planId, Optional<UUID> after, int limit) {
    if (limit < 1 || limit > MAX_ALLOCATION_PAGE) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_ALLOCATION_PAGE);
    }
    return read(
            actor.userId(),
            () ->
                plans.findRun(planId).map(run -> {
                  List<AllocationRow> rows = plans.allocationsAfter(planId, after, limit + 1);
                  boolean more = rows.size() > limit;
                  List<AllocationRow> page = more ? rows.subList(0, limit) : rows;
                  return new AllocationPageView(
                      planId,
                      run.planVersion(),
                      page.stream().map(PlanDataQuery::toLine).toList(),
                      more ? Optional.of(page.get(page.size() - 1).orderId().toString()) : Optional.empty());
                }))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId));
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
    return options(orderId, (p, tripIds) -> toView(orderId, p, tripIds));
  }

  private <T> List<T> options(UUID orderId, java.util.function.BiFunction<Placement, Map<String, UUID>, T> view) {
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
        .map(p -> view.apply(p, tripIds))
        .toList();
  }

  private static PlacementView toPlacement(Placement p, Map<String, UUID> tripIds) {
    Optional<ConstraintResult> failed = ConstraintRegistry.firstFailure(p.checks());
    return new PlacementView(
        p.vehicleId(),
        p.tripNumber(),
        p.joins(),
        p.joins() ? Optional.ofNullable(tripIds.get(p.vehicleId() + "/" + p.tripNumber())) : Optional.empty(),
        p.feasible(),
        failed.map(ConstraintResult::ruleId),
        failed.map(ConstraintResult::reason).orElse(p.joins() ? "Fits on the trip" : "Fits as a new trip"),
        p.checks().stream().map(PlanDataQuery::toView).toList());
  }

  private TripPreview swapPreview(UUID outOrderId, UUID inOrderId) {
    RunRow row =
        plans.openDraftsWithOrder(outOrderId).stream().findFirst()
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "order " + outOrderId + " is in no open draft"));
    Opened opened = drafts.rebuild(row);
    PlanOrder out = opened.built().orders().get(outOrderId);
    PlanOrder in = opened.built().orders().get(inOrderId);
    if (out == null || in == null) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED, "an order is no longer in the demand", List.of("PLN-07"));
    }
    PlanContext context = opened.built().problem().context();
    return tripPreview(drafts.run(opened).proposeSwap(out, in, registry, context), context);
  }

  private TripPreview sequencePreview(UUID tripId, List<UUID> orderIds) {
    RunRow row =
        plans.runsWithTrip(tripId).stream().findFirst()
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No live plan carries trip " + tripId));
    if (row.status() != PlanStatus.DRAFT) {
      throw new DomainException(
          ErrorCode.CONFLICT, "plan " + row.planId() + " is " + row.status() + "; only a draft changes", List.of("R-PLN-28"));
    }
    Opened opened = drafts.rebuild(row);
    PlanningRun run = drafts.run(opened);
    Place place = revisions.locate(row.planId(), tripId, run);
    PlanContext context = opened.built().problem().context();
    return tripPreview(run.proposeSequence(place.vehicleId(), place.tripNumber(), orderIds, registry, context), context);
  }

  /** The proposed trip as it would run: stops in order with their arrivals, and the registry's checks. */
  private static TripPreview tripPreview(Proposed proposed, PlanContext context) {
    List<StopView> stops =
        TripTimeline.schedule(proposed.day(), context.travel(), context.rules()).stream()
            .filter(s -> s.tripNumber() == proposed.tripNumber())
            .flatMap(s -> s.stops().stream())
            .map(
                t ->
                    new StopView(
                        t.sequence(), t.order().orderId(), t.order().outletId(), t.arrival(),
                        t.order().windowOpen().orElse(null), t.order().windowClose().orElse(null),
                        t.order().serviceMinutes()))
            .toList();
    return new TripPreview(
        proposed.day().vehicleId(), proposed.tripNumber(), proposed.feasible(), stops,
        proposed.checks().stream().map(PlanDataQuery::toView).toList());
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
        p.checks().stream().map(PlanDataQuery::toView).toList(),
        AllocationSource.ENGINE,
        false,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
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

  /**
   * A draft as the dispatcher sees it, for a saved plan. Callable inside a
   * command's transaction: a draft is never scored, so Intelligence is not asked.
   */
  PlanView draftView(RunRow run) {
    return assemble(run, true);
  }

  /** Called inside a read, so the children come from the same snapshot as the run. */
  private PlanView assemble(RunRow run) {
    return assemble(run, withoutPredictor(run.planId()));
  }

  private PlanView assemble(RunRow run, boolean withoutPredictor) {
    List<AllocationRow> allocations = plans.allocations(run.planId());
    Map<String, LocalDate> lastServed = plans.lastServed(run.depotCode(), run.serviceDate());
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
        run.generatedAt(),
        withoutPredictor,
        trips,
        allocations.stream().map(a -> toView(a, lastServed)).toList(),
        run.rowVersion(),
        run.engine(),
        run.improvement()
            .map(i -> new ImprovementView(i.greedyServed(), i.greedyDeferred(), i.served(), i.deferred(), i.improved(),
                i.chilledVolumeGainedM3(), i.stoppedBy().name(), i.chilledCandidates(), i.chilledSearched())));
  }

  /** Called inside a read, like {@link #assemble}. */
  private PlanSummaryView summarise(RunRow run) {
    List<AllocationRow> allocations = plans.allocations(run.planId());
    Map<UUID, Long> stops =
        allocations.stream()
            .filter(a -> a.decision() == AllocationDecision.SERVED)
            .collect(Collectors.groupingBy(a -> a.tripId().orElseThrow(), Collectors.counting()));
    return new PlanSummaryView(
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
        run.rowVersion(),
        count(allocations, AllocationDecision.SERVED),
        count(allocations, AllocationDecision.DEFERRED),
        count(allocations, AllocationDecision.UNSERVABLE),
        plans.trips(run.planId()).stream()
            .map(t -> new TripSummaryView(
                t.tripId(), t.vehicleId(), t.tripNumber(), t.brandCode(), t.districtName(), t.temperature(),
                t.weightKg(), t.volumeM3(), t.plannedMinutes(), t.plannedDeparture(),
                stops.getOrDefault(t.tripId(), 0L).intValue()))
            .toList());
  }

  private static int count(List<AllocationRow> allocations, AllocationDecision decision) {
    return (int) allocations.stream().filter(a -> a.decision() == decision).count();
  }

  private static AllocationLineView toLine(AllocationRow a) {
    AllocationView view = toView(a, Map.of());
    return new AllocationLineView(
        a.orderId(), a.decision(), a.tripId(), a.stopSequence(), a.plannedArrival(),
        a.bindingRule(), a.reason(), view.checks());
  }

  /**
   * Intelligence answers whether a model scored this plan (issue #16). Allocation
   * keeps the booklet allowances either way (R-ML-05); this says whether learned
   * late risk exists for it. A draft is never scored, and with no Intelligence
   * module deployed every plan is without a predictor, as before.
   */
  private boolean withoutPredictor(UUID planId) {
    PredictionQuery intelligence = predictions.getIfAvailable();
    if (intelligence == null) {
      return true;
    }
    return intelligence.planScoring(planId).map(PlanScoringView::withoutPredictor).orElse(true);
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

  private static AllocationView toView(AllocationRow a, Map<String, LocalDate> lastServed) {
    return new AllocationView(
        a.orderId(),
        a.decision(),
        a.tripId(),
        a.bindingRule(),
        a.reason(),
        a.checks().stream()
            .map(c -> new ConstraintResultView(c.ruleId(), c.passed(), c.reason(), c.slack()))
            .toList(),
        AllocationSource.valueOf(a.source().toUpperCase(java.util.Locale.ROOT)),
        a.locked(),
        a.decidedBy(),
        a.decidedAt(),
        Optional.ofNullable(lastServed.get(a.outletId())));
  }

  private static DeferralView toView(DeferralRow d) {
    return new DeferralView(d.orderId(), d.outletId(), d.serviceDate(), d.ruleId(), d.reason(), d.skipCount());
  }
}
