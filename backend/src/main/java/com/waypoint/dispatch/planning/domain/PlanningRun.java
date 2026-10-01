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
 * @param deferredBy who deferred each deferred order: the engine's actor or the
 *     dispatcher (rule 8)
 */
public record PlanningRun(
    UUID planId,
    String depotCode,
    LocalDate serviceDate,
    int planVersion,
    PlanStatus status,
    Stamps stamps,
    Optional<UUID> supersedes,
    String demandFingerprint,
    boolean stale,
    boolean partial,
    String engine,
    List<VehicleDay> days,
    List<OrderDecision> decisions,
    Map<UUID, UUID> deferredBy,
    long rowVersion) {

  /** R-PLN-19: the dispatcher decides what defers and records why. */
  public static final String MANUAL_DEFERRAL_RULE = "R-PLN-19";

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
        planId, depotCode, serviceDate, planVersion, PlanStatus.DRAFT, stamps, supersedes,
        demandFingerprint, false, result.partial(), result.engine(), result.days(), result.decisions(), by, 1);
  }

  public AllocationResult result() {
    return new AllocationResult(days, decisions, partial, engine);
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
        nextPlanId, depotCode, serviceDate, nextVersion, PlanStatus.DRAFT, stamps, supersedes,
        demandFingerprint, false, partial, engine, nextDays, refreshed, nextDeferredBy, 1);
  }
}
