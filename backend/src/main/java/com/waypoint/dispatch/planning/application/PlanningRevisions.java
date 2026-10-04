package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningProblems.Built;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.domain.ConstraintRegistry;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PlanningRun.Replanned;
import com.waypoint.dispatch.planning.domain.PlanningRun.Stamps;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.domain.VehicleDay;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.TripRow;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Changing a published plan: a revision draft built under the versions in force
 * now, and the replanning of one trip or one vehicle's trips inside it (PLN-04,
 * R-LOD-06, R-LOD-09). Nothing here publishes; {@link PlanPublication} does.
 * Shared by the handlers, the consumers and the previews, so a preview is the
 * same computation as the command it previews.
 */
@Component
class PlanningRevisions {
  private final JdbcPlanRepository plans;
  private final PlanningProblems problems;
  private final PlanningDrafts drafts;
  private final ReferenceQuery reference;
  private final ConstraintRegistry registry;

  PlanningRevisions(
      JdbcPlanRepository plans,
      PlanningProblems problems,
      PlanningDrafts drafts,
      ReferenceQuery reference,
      ConstraintRegistry registry) {
    this.plans = plans;
    this.problems = problems;
    this.drafts = drafts;
    this.reference = reference;
    this.registry = registry;
  }

  /** A draft revising {@code published}, not yet stored, with the problem it was built on. */
  record Revision(RunRow published, PlanningRun run, Built built) {}

  /** A trip's place in a run: which vehicle and which of its trips. */
  record Place(String vehicleId, int tripNumber) {}

  /** The published plan at the version the caller saw. Out of scope and absent look the same. */
  RunRow published(UUID planId, long expected) {
    RunRow row =
        plans.findRun(planId).orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId));
    if (row.status() != PlanStatus.PUBLISHED) {
      throw new DomainException(
          ErrorCode.CONFLICT, "plan " + planId + " is " + row.status() + "; only the published plan is revised",
          List.of("R-PLN-28"));
    }
    if (row.rowVersion() != expected) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT, "plan " + planId + " is at row version " + row.rowVersion() + ", not " + expected);
    }
    return row;
  }

  Revision revise(RunRow published, String reason, UUID actor, UUID nextId, int nextVersion) {
    if (published.status() != PlanStatus.PUBLISHED) {
      throw new DomainException(
          ErrorCode.CONFLICT, "plan " + published.planId() + " is " + published.status() + "; only a published plan is revised",
          List.of("R-PLN-28"));
    }
    UUID referenceVersion =
        reference.currentVersionId()
            .orElseThrow(() -> new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "No reference version is loaded"));
    RuleSet rules =
        plans.effectiveRuleSet(published.serviceDate())
            .orElseThrow(
                () -> new DomainException(
                    ErrorCode.CONSTRAINT_VIOLATED, "no rule set is in force on " + published.serviceDate(), List.of("POL-10")));
    PriorityPolicy policy =
        plans.effectivePolicy(published.depotCode(), published.serviceDate())
            .orElseThrow(
                () -> new DomainException(
                    ErrorCode.CONSTRAINT_VIOLATED, "no deferral priority policy is in force", List.of("POL-10", "R-PLN-21")));
    List<AllocationRow> allocations = plans.allocations(published.planId());
    Set<UUID> carried = allocations.stream().map(AllocationRow::orderId).collect(Collectors.toSet());
    Built built =
        problems.build(
            published.depotCode(), published.serviceDate(), referenceVersion, rules, policy,
            Optional.of(published.planId()), carried);
    PlanningRun base =
        PlanRecords.load(
            published, plans.trips(published.planId()), allocations, plans.deferrals(published.planId()), built, true);
    PlanningRun run =
        base.revision(
            nextId, nextVersion, new Stamps(referenceVersion, rules.id(), policy.id()), built.fingerprint(),
            built.orders().keySet(), reason, actor, registry, built.problem().context());
    return new Revision(published, run, built);
  }

  /** The trip a stored trip id names, found in {@code run} by the orders it still carries. */
  Place locate(UUID planId, UUID tripId, PlanningRun run) {
    TripRow trip =
        plans.trips(planId).stream()
            .filter(t -> t.tripId().equals(tripId))
            .findFirst()
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No trip " + tripId + " in plan " + planId));
    Set<UUID> orders =
        plans.allocations(planId).stream()
            .filter(a -> a.tripId().equals(Optional.of(tripId)))
            .map(AllocationRow::orderId)
            .collect(Collectors.toSet());
    for (VehicleDay day : run.days()) {
      for (int n = 1; n <= day.trips().size(); n++) {
        if (day.trip(n).orders().stream().anyMatch(o -> orders.contains(o.orderId()))) {
          return new Place(day.vehicleId(), n);
        }
      }
    }
    throw new DomainException(
        ErrorCode.CONFLICT, "trip " + tripId + " on " + trip.vehicleId() + " carries no live orders any more");
  }

  /**
   * Who could take a trip, in the order to try them: the requested replacement
   * first, then idle vehicles of the same kind (reefer, van), then the rest of
   * the available fleet. A requested replacement is tried even when
   * unavailable, so the answer names R-FLT-03 instead of silently skipping it.
   */
  List<FleetVehicle> candidates(PlanningRun run, Built built, String current, Optional<String> replacement) {
    FleetVehicle now = built.fleet().get(current);
    List<FleetVehicle> out = new ArrayList<>();
    replacement.ifPresent(
        id ->
            out.add(
                Optional.ofNullable(built.fleet().get(id))
                    .orElseThrow(
                        () -> new DomainException(ErrorCode.VALIDATION_FAILED, id + " is not a vehicle of depot " + run.depotCode()))));
    Set<String> busy = run.days().stream().map(VehicleDay::vehicleId).collect(Collectors.toSet());
    built.fleet().values().stream()
        .filter(FleetVehicle::available)
        .filter(v -> !v.vehicleId().equals(current) && !replacement.equals(Optional.of(v.vehicleId())))
        .sorted(
            Comparator.comparing((FleetVehicle v) -> busy.contains(v.vehicleId()))
                .thenComparing(v -> now == null || v.reefer() != now.reefer() || v.van() != now.van())
                .thenComparing(FleetVehicle::vehicleId))
        .forEach(out::add);
    return out;
  }

  /** Replans one trip of an open run; a revision keeps its own id and version. */
  Replanned replanTrip(Revision revision, Place place, Optional<String> replacement, String reason, UUID actor) {
    PlanningRun run = revision.run();
    return replanTrip(run, revision.built(), place, replacement, reason, actor, run.planId(), run.planVersion());
  }

  Replanned replanTrip(
      PlanningRun run,
      Built built,
      Place place,
      Optional<String> replacement,
      String reason,
      UUID actor,
      UUID nextId,
      int nextVersion) {
    return run.replanTrip(
        nextId, nextVersion, place.vehicleId(), place.tripNumber(),
        candidates(run, built, place.vehicleId(), replacement), reason, actor, registry, built.problem().context());
  }

  /** Every trip of a vehicle that can no longer run, one at a time, never onto itself (PLN-04, FLT-01). */
  PlanningRun replanVehicle(Revision revision, String vehicleId, String reason, UUID actor) {
    PlanningRun run = revision.run();
    for (int guard = 0; guard < 10; guard++) {
      boolean busy = run.days().stream().anyMatch(d -> d.vehicleId().equals(vehicleId));
      if (!busy) {
        return run;
      }
      run =
          run.replanTrip(
                  run.planId(), run.planVersion(), vehicleId, 1,
                  candidates(run, revision.built(), vehicleId, Optional.empty()), reason, actor, registry,
                  revision.built().problem().context())
              .run();
    }
    throw new IllegalStateException("vehicle " + vehicleId + " still has trips after replanning");
  }

  /** Stores a revision as the day's one open draft; unchanged trips keep their ids. */
  void store(RunRow published, PlanningRun run, Built built, UUID actor, Instant now, UUID commandId) {
    drafts.cancelOpen(published.depotCode(), published.serviceDate(), now);
    drafts.write(run, built, actor, now, commandId, drafts.predecessor(published.planId()));
  }
}
