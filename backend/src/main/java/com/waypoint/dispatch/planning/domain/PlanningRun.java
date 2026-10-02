package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.Constraint.Candidate;
import com.waypoint.dispatch.planning.domain.PlanVerification.Violation;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One planning run for a depot and day: the engine's answer plus every decision
 * a dispatcher layered on it, and the three versions it was built under.
 *
 * <p>A version is never edited. An override or a deferral returns the next
 * version of the draft, which the caller stores while cancelling this one, so
 * two dispatchers editing one draft collide on its row version and the loser is
 * shown what changed (PLN-06). Publication is the repository's status change;
 * this decides whether it may happen.
 *
 * @param revisionReason why this version revises the published plan it
 *     supersedes; empty for a first plan
 * @param deferredBy who deferred each deferred order: the engine's actor or the
 *     dispatcher (rule 8)
 * @param improvement what the engine's second pass achieved over its first,
 *     kept through every version of the draft (issue #92)
 */
public record PlanningRun(
    UUID planId,
    String depotCode,
    LocalDate serviceDate,
    int planVersion,
    PlanStatus status,
    Stamps stamps,
    Optional<UUID> supersedes,
    Optional<String> revisionReason,
    String demandFingerprint,
    boolean stale,
    boolean partial,
    String engine,
    Optional<ScarceFleetReplan.Summary> improvement,
    List<VehicleDay> days,
    List<OrderDecision> decisions,
    Map<UUID, UUID> deferredBy,
    long rowVersion) {

  /** R-PLN-19: the dispatcher decides what defers and records why. */
  public static final String MANUAL_DEFERRAL_RULE = "R-PLN-19";

  /** An order a revision found that the published plan never decided (PLN-07). */
  public static final String ARRIVED_AFTER_PUBLICATION_RULE = "PLN-07";

  /** The versions a run was built under, so it can be replayed and gated (POL-03). */
  public record Stamps(UUID referenceVersionId, UUID ruleSetId, UUID policyVersionId) {}

  public PlanningRun {
    days = days.stream().filter(d -> !d.trips().isEmpty()).sorted(Comparator.comparing(VehicleDay::vehicleId)).toList();
    decisions = decisions.stream().sorted(Comparator.comparing(OrderDecision::orderId)).toList();
    deferredBy = Map.copyOf(deferredBy);
  }

  /** A first draft from an engine result. Every engine deferral is the engine actor's. */
  public static PlanningRun draft(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      Stamps stamps,
      Optional<UUID> supersedes,
      String demandFingerprint,
      AllocationResult result,
      UUID engineActor) {
    Map<UUID, UUID> by = new HashMap<>();
    result.decisions().stream()
        .filter(d -> d.decision() == AllocationDecision.DEFERRED)
        .forEach(d -> by.put(d.orderId(), engineActor));
    return new PlanningRun(
        planId, depotCode, serviceDate, planVersion, PlanStatus.DRAFT, stamps, supersedes, Optional.empty(),
        demandFingerprint, false, result.partial(), result.engine(), result.improvement(), result.days(),
        result.decisions(), by, 1);
  }

  public AllocationResult result() {
    return new AllocationResult(days, decisions, partial, engine, improvement);
  }

  public Optional<OrderDecision> decisionFor(UUID orderId) {
    return decisions.stream().filter(d -> d.orderId().equals(orderId)).findFirst();
  }

  public long count(AllocationDecision decision) {
    return decisions.stream().filter(d -> d.decision() == decision).count();
  }

  /** The whole plan against the whole registry: the engine's check and the publication gate's. */
  public List<Violation> verify(Set<UUID> demand, PlanContext context, ConstraintRegistry registry) {
    return PlanVerification.verify(result(), demand, context, registry);
  }

  /** Only a draft that nothing has invalidated may change or be published. */
  public void requireOpenDraft() {
    if (status != PlanStatus.DRAFT) {
      throw new DomainException(
          ErrorCode.CONFLICT, "plan " + planId + " is " + status + "; only a draft changes", List.of("R-PLN-28"));
    }
  }

  /**
   * Moves an order onto a vehicle's trip. Refused, with every failing rule,
   * unless the vehicle's whole day still passes the registry: an override is a
   * decision a dispatcher may take, never a way around a rule.
   *
   * @param tripNumber an existing trip of the vehicle to join, or one past the
   *     last to open a new trip
   */
  public PlanningRun override(
      UUID nextPlanId,
      int nextVersion,
      PlanOrder order,
      FleetVehicle vehicle,
      int tripNumber,
      String reason,
      ConstraintRegistry registry,
      PlanContext context) {
    requireOpenDraft();
    decisionOrThrow(order.orderId());
    List<VehicleDay> next = new ArrayList<>(days.stream().map(d -> d.without(order.orderId())).toList());
    VehicleDay target =
        next.stream()
            .filter(d -> d.vehicleId().equals(vehicle.vehicleId()))
            .findFirst()
            .orElse(VehicleDay.idle(vehicle));
    int trips = target.trips().size();
    if (tripNumber < 1 || tripNumber > trips + 1) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          vehicle.vehicleId() + " has " + trips + " trips; choose 1 to " + (trips + 1));
    }
    VehicleDay placed = tripNumber <= trips ? target.withJoined(tripNumber, order) : target.withNewTrip(order);
    List<ConstraintResult> results = registry.evaluate(new Candidate(placed, context, Set.of()));
    List<ConstraintResult> failed = results.stream().filter(r -> !r.passed()).toList();
    if (!failed.isEmpty()) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "override refused: " + String.join("; ", failed.stream().map(r -> r.ruleId() + " " + r.reason()).toList()),
          failed.stream().map(ConstraintResult::ruleId).distinct().toList());
    }
    next.removeIf(d -> d.vehicleId().equals(vehicle.vehicleId()));
    next.add(placed);

    Map<UUID, String> reasons = Map.of(order.orderId(), "override: " + reason);
    List<OrderDecision> changed = replace(order.orderId(), served(order.orderId(), reasons.get(order.orderId())));
    Map<UUID, UUID> by = new HashMap<>(deferredBy);
    by.remove(order.orderId());
    return successor(nextPlanId, nextVersion, next, changed, by, reasons.keySet(), registry, context);
  }

  /** Takes a served order off its trip with the dispatcher's reason (R-PLN-19). */
  public PlanningRun defer(
      UUID nextPlanId,
      int nextVersion,
      UUID orderId,
      String reason,
      UUID actor,
      ConstraintRegistry registry,
      PlanContext context) {
    requireOpenDraft();
    OrderDecision current = decisionOrThrow(orderId);
    if (current.decision() != AllocationDecision.SERVED) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "order " + orderId + " is already " + current.decision());
    }
    List<VehicleDay> next = days.stream().map(d -> d.without(orderId)).toList();
    OrderDecision deferred =
        new OrderDecision(
            orderId, AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(),
            Optional.of(MANUAL_DEFERRAL_RULE), "deferred by dispatcher: " + reason, List.of());
    Map<UUID, UUID> by = new HashMap<>(deferredBy);
    by.put(orderId, actor);
    return successor(nextPlanId, nextVersion, next, replace(orderId, deferred), by, Set.of(), registry, context);
  }

  /**
   * A draft that revises this published plan under the versions in force now.
   * Orders cancelled since publication leave their trips; orders that arrived
   * since are deferred under {@code PLN-07} until a dispatcher places them,
   * because a revision never silently replans what was already communicated.
   *
   * @param demand every order the revision decides: this plan's, still live,
   *     and any new confirmed demand
   */
  public PlanningRun revision(
      UUID nextPlanId,
      int nextVersion,
      Stamps nextStamps,
      String nextFingerprint,
      Set<UUID> demand,
      String reason,
      UUID actor,
      ConstraintRegistry registry,
      PlanContext context) {
    if (status != PlanStatus.PUBLISHED) {
      throw new DomainException(
          ErrorCode.CONFLICT, "plan " + planId + " is " + status + "; only a published plan is revised",
          List.of("R-PLN-28"));
    }
    List<VehicleDay> nextDays = new ArrayList<>(days);
    List<OrderDecision> nextDecisions = new ArrayList<>();
    Map<UUID, UUID> by = new HashMap<>(deferredBy);
    for (OrderDecision d : decisions) {
      if (demand.contains(d.orderId())) {
        nextDecisions.add(d);
      } else {
        nextDays = nextDays.stream().map(v -> v.without(d.orderId())).toList();
        by.remove(d.orderId());
      }
    }
    Set<UUID> decided = new java.util.HashSet<>(nextDecisions.stream().map(OrderDecision::orderId).toList());
    for (UUID orderId : demand) {
      if (!decided.contains(orderId)) {
        nextDecisions.add(
            new OrderDecision(
                orderId, AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(),
                Optional.of(ARRIVED_AFTER_PUBLICATION_RULE),
                "arrived after the plan was published; place it with an override", List.of()));
        by.put(orderId, actor);
      }
    }
    return successor(
        nextPlanId, nextVersion, nextStamps, Optional.of(planId), Optional.of(reason), nextFingerprint,
        nextDays, nextDecisions, by, Set.of(), registry, context);
  }

  /**
   * One vehicle's trip moved whole onto another vehicle as a new trip there:
   * the interchange check (R-LOD-06). Read only.
   */
  public TripMove moveTrip(
      String vehicleId, int tripNumber, FleetVehicle replacement, ConstraintRegistry registry, PlanContext context) {
    VehicleDay from = dayOf(vehicleId);
    if (tripNumber < 1 || tripNumber > from.trips().size()) {
      throw new DomainException(ErrorCode.NOT_FOUND, vehicleId + " has no trip " + tripNumber + " in plan " + planId);
    }
    if (replacement.vehicleId().equals(vehicleId)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "the replacement is the vehicle already on the trip");
    }
    Trip trip = from.trip(tripNumber);
    VehicleDay remaining = from;
    for (PlanOrder o : trip.orders()) {
      remaining = remaining.without(o.orderId());
    }
    VehicleDay target =
        days.stream().filter(d -> d.vehicleId().equals(replacement.vehicleId())).findFirst()
            .orElse(VehicleDay.idle(replacement));
    PlanOrder first = trip.orders().get(0);
    VehicleDay placed = target.withNewTrip(first);
    int newNumber = placed.tripNumberOf(first.orderId()).orElseThrow();
    for (PlanOrder o : trip.orders().subList(1, trip.orders().size())) {
      placed = placed.withJoined(newNumber, o);
    }
    List<ConstraintResult> checks = registry.evaluate(new Candidate(placed, context, Set.of()));
    return new TripMove(trip, remaining, placed, checks);
  }

  /** @param to the replacement's whole day with the trip on it */
  public record TripMove(Trip trip, VehicleDay from, VehicleDay to, List<ConstraintResult> checks) {
    public TripMove {
      checks = List.copyOf(checks);
    }

    public boolean feasible() {
      return ConstraintRegistry.allPass(checks);
    }
  }

  /** @param movedTo the vehicle that took the trip; empty when it deferred as a unit */
  public record Replanned(PlanningRun run, Optional<String> movedTo, List<ConstraintResult> checks) {}

  /**
   * Replans one trip: the first candidate that can take it whole does
   * (R-LOD-06); with none, every order on it defers together under the rule
   * that stopped the closest candidate, and keeps its identity (R-LOD-09, LOD-03).
   *
   * @param candidates in the order to try them, the requested replacement first
   */
  public Replanned replanTrip(
      UUID nextPlanId,
      int nextVersion,
      String vehicleId,
      int tripNumber,
      List<FleetVehicle> candidates,
      String reason,
      UUID actor,
      ConstraintRegistry registry,
      PlanContext context) {
    requireOpenDraft();
    TripMove closest = null;
    for (FleetVehicle candidate : candidates) {
      if (candidate.vehicleId().equals(vehicleId)) {
        continue;
      }
      TripMove move = moveTrip(vehicleId, tripNumber, candidate, registry, context);
      if (move.feasible()) {
        List<VehicleDay> next = new ArrayList<>(days);
        next.removeIf(d -> d.vehicleId().equals(vehicleId) || d.vehicleId().equals(candidate.vehicleId()));
        next.add(move.from());
        next.add(move.to());
        Set<UUID> moved = new java.util.HashSet<>(move.trip().orders().stream().map(PlanOrder::orderId).toList());
        List<OrderDecision> changed =
            decisions.stream()
                .map(d -> moved.contains(d.orderId()) ? served(d.orderId(), "replanned onto " + candidate.vehicleId() + ": " + reason) : d)
                .toList();
        PlanningRun run = successor(nextPlanId, nextVersion, next, changed, deferredBy, moved, registry, context);
        return new Replanned(run, Optional.of(candidate.vehicleId()), move.checks());
      }
      if (closest == null || ConstraintRegistry.failures(move.checks()) < ConstraintRegistry.failures(closest.checks())) {
        closest = move;
      }
    }
    Trip trip = dayOf(vehicleId).trip(tripNumber);
    String rule =
        closest == null
            ? "R-LOD-09"
            : ConstraintRegistry.firstFailure(closest.checks()).map(ConstraintResult::ruleId).orElse("R-LOD-09");
    String why =
        closest == null
            ? "no other vehicle is available"
            : ConstraintRegistry.firstFailure(closest.checks()).map(ConstraintResult::reason).orElse("");
    List<ConstraintResult> evidence = closest == null ? List.of() : closest.checks();
    Set<UUID> onTrip = new java.util.HashSet<>(trip.orders().stream().map(PlanOrder::orderId).toList());
    List<VehicleDay> next = days;
    Map<UUID, UUID> by = new HashMap<>(deferredBy);
    for (UUID orderId : onTrip) {
      next = next.stream().map(d -> d.without(orderId)).toList();
      by.put(orderId, actor);
    }
    String explanation = "trip deferred as a unit (" + reason + "): no vehicle could take it whole; " + why;
    List<OrderDecision> changed =
        decisions.stream()
            .map(
                d ->
                    onTrip.contains(d.orderId())
                        ? new OrderDecision(
                            d.orderId(), AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(),
                            Optional.of(rule), explanation, evidence)
                        : d)
            .toList();
    PlanningRun run = successor(nextPlanId, nextVersion, next, changed, by, Set.of(), registry, context);
    return new Replanned(run, Optional.empty(), evidence);
  }

  /** Where an order could go: every trip it could join and a new trip on every vehicle, each with its checks. */
  public List<Placement> options(
      PlanOrder order, List<FleetVehicle> fleet, ConstraintRegistry registry, PlanContext context) {
    List<Placement> out = new ArrayList<>();
    for (FleetVehicle vehicle : fleet.stream().sorted(Comparator.comparing(FleetVehicle::vehicleId)).toList()) {
      VehicleDay base =
          days.stream().filter(d -> d.vehicleId().equals(vehicle.vehicleId())).findFirst()
              .orElse(VehicleDay.idle(vehicle))
              .without(order.orderId());
      for (int n = 1; n <= base.trips().size() + 1; n++) {
        VehicleDay placed = n <= base.trips().size() ? base.withJoined(n, order) : base.withNewTrip(order);
        int trip = placed.tripNumberOf(order.orderId()).orElseThrow();
        out.add(
            new Placement(
                vehicle.vehicleId(), trip, n <= base.trips().size(),
                registry.evaluate(new Candidate(placed, context, Set.of()))));
      }
    }
    return out;
  }

  /** @param joins true to join an existing trip, false to open a new one */
  public record Placement(String vehicleId, int tripNumber, boolean joins, List<ConstraintResult> checks) {
    public Placement {
      checks = List.copyOf(checks);
    }

    public boolean feasible() {
      return ConstraintRegistry.allPass(checks);
    }
  }

  private VehicleDay dayOf(String vehicleId) {
    return days.stream()
        .filter(d -> d.vehicleId().equals(vehicleId))
        .findFirst()
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, vehicleId + " has no trips in plan " + planId));
  }

  // ---- internals -----------------------------------------------------------

  private OrderDecision decisionOrThrow(UUID orderId) {
    return decisionFor(orderId)
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "order " + orderId + " is not in plan " + planId));
  }

  private List<OrderDecision> replace(UUID orderId, OrderDecision replacement) {
    return decisions.stream().map(d -> d.orderId().equals(orderId) ? replacement : d).toList();
  }

  /** A placeholder the successor fills in with the vehicle, trip and checks. */
  private static OrderDecision served(UUID orderId, String reason) {
    return new OrderDecision(
        orderId, AllocationDecision.SERVED, Optional.empty(), Optional.empty(), Optional.empty(), reason, List.of());
  }

  /**
   * The next draft version. Trip numbers can shift when a trip empties or a
   * Fresh trip is added, so every served decision is re-derived from the days,
   * with fresh checks; a reason survives while its order stays where it was.
   */
  private PlanningRun successor(
      UUID nextPlanId,
      int nextVersion,
      List<VehicleDay> nextDays,
      List<OrderDecision> nextDecisions,
      Map<UUID, UUID> nextDeferredBy,
      Set<UUID> keepReason,
      ConstraintRegistry registry,
      PlanContext context) {
    return successor(
        nextPlanId, nextVersion, stamps, supersedes, revisionReason, demandFingerprint, nextDays, nextDecisions,
        nextDeferredBy, keepReason, registry, context);
  }

  private PlanningRun successor(
      UUID nextPlanId,
      int nextVersion,
      Stamps nextStamps,
      Optional<UUID> nextSupersedes,
      Optional<String> nextReason,
      String nextFingerprint,
      List<VehicleDay> nextDays,
      List<OrderDecision> nextDecisions,
      Map<UUID, UUID> nextDeferredBy,
      Set<UUID> keepReason,
      ConstraintRegistry registry,
      PlanContext context) {
    List<OrderDecision> refreshed = new ArrayList<>();
    for (OrderDecision d : nextDecisions) {
      if (d.decision() != AllocationDecision.SERVED) {
        refreshed.add(d);
        continue;
      }
      VehicleDay day =
          nextDays.stream()
              .filter(v -> v.carries(d.orderId()))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("served order " + d.orderId() + " is on no vehicle"));
      int trip = day.tripNumberOf(d.orderId()).orElseThrow();
      Optional<OrderDecision> before = decisionFor(d.orderId());
      boolean unmoved =
          before.map(b -> b.vehicleId().equals(Optional.of(day.vehicleId())) && b.tripNumber().equals(Optional.of(trip)))
              .orElse(false);
      String reason =
          keepReason.contains(d.orderId()) || unmoved
              ? d.reason()
              : "served on " + day.vehicleId() + " trip " + trip;
      refreshed.add(
          new OrderDecision(
              d.orderId(), AllocationDecision.SERVED, Optional.of(day.vehicleId()), Optional.of(trip),
              Optional.empty(), reason, registry.evaluate(new Candidate(day, context, Set.of()))));
    }
    return new PlanningRun(
        nextPlanId, depotCode, serviceDate, nextVersion, PlanStatus.DRAFT, nextStamps, nextSupersedes,
        nextReason, nextFingerprint, false, partial, engine, improvement, nextDays, refreshed, nextDeferredBy, 1);
  }
}
