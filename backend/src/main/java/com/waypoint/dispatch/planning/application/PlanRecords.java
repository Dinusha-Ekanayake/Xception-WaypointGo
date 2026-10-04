package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.application.PlanningProblems.Built;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.DistrictTravel;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.FuelLedger;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.domain.PlanningRun.Mark;
import com.waypoint.dispatch.planning.domain.PlanningRun.Source;
import com.waypoint.dispatch.planning.domain.PlanningRun.Stamps;
import com.waypoint.dispatch.planning.domain.TemperatureClass;
import com.waypoint.dispatch.planning.domain.Trip;
import com.waypoint.dispatch.planning.domain.TripTimeline;
import com.waypoint.dispatch.planning.domain.TripTimeline.StopTime;
import com.waypoint.dispatch.planning.domain.TripTimeline.TripSchedule;
import com.waypoint.dispatch.planning.domain.VehicleDay;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.DeferralRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.FuelRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.LegRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.RunRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.TripRow;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Translation between the {@link PlanningRun} aggregate and Planning's rows and
 * events. No decisions are made here; the schedule, litres and legs are the
 * domain's {@link TripTimeline} and {@link FuelLedger}, written down.
 */
final class PlanRecords {
  private PlanRecords() {}

  record Rows(
      RunRow run,
      List<TripRow> trips,
      List<AllocationRow> allocations,
      List<LegRow> legs,
      List<FuelRow> fuel,
      List<DeferralRow> deferrals) {}

  /**
   * The version before this one, so a trip carrying the same orders keeps its
   * id (PLN-04, R-LOD-06) and a deferral already communicated is copied rather
   * than counted again.
   */
  record Predecessor(
      Map<Set<UUID>, UUID> tripIds, Map<UUID, DeferralRow> deferrals, Map<UUID, AllocationRow> allocations) {
    static final Predecessor NONE = new Predecessor(Map.of(), Map.of(), Map.of());

    static Predecessor of(List<TripRow> trips, List<AllocationRow> allocations, List<DeferralRow> deferrals) {
      Map<UUID, Set<UUID>> orders = new HashMap<>();
      for (AllocationRow a : allocations) {
        a.tripId().ifPresent(t -> orders.computeIfAbsent(t, k -> new java.util.HashSet<>()).add(a.orderId()));
      }
      Map<Set<UUID>, UUID> ids = new HashMap<>();
      for (TripRow t : trips) {
        ids.put(Set.copyOf(orders.getOrDefault(t.tripId(), Set.of())), t.tripId());
      }
      return new Predecessor(
          ids,
          deferrals.stream().collect(Collectors.toMap(DeferralRow::orderId, d -> d)),
          allocations.stream().collect(Collectors.toMap(AllocationRow::orderId, a -> a)));
    }
  }

  static Rows of(PlanningRun run, Built built, UUID generatedBy, Instant at, Supplier<UUID> ids) {
    return of(run, built, generatedBy, at, ids, Predecessor.NONE);
  }

  static Rows of(
      PlanningRun run, Built built, UUID generatedBy, Instant at, Supplier<UUID> ids, Predecessor predecessor) {
    Map<String, DistrictTravel> travel = built.problem().travel();
    List<TripRow> trips = new ArrayList<>();
    List<LegRow> legs = new ArrayList<>();
    List<FuelRow> fuel = new ArrayList<>();
    Map<UUID, Stop> stops = new HashMap<>();

    for (VehicleDay day : run.days()) {
      FleetVehicle vehicle = day.vehicle();
      for (TripSchedule s : TripTimeline.schedule(day, travel, built.problem().rules())) {
        Trip trip = s.trip();
        DistrictTravel d = travel.get(trip.district());
        Set<UUID> carried = trip.orders().stream().map(PlanOrder::orderId).collect(Collectors.toSet());
        UUID tripId = Optional.ofNullable(predecessor.tripIds().get(carried)).orElseGet(ids);
        trips.add(
            new TripRow(
                tripId,
                vehicle.vehicleId(),
                s.tripNumber(),
                trip.brand(),
                trip.district(),
                trip.temperature().code(),
                trip.weightKg(),
                trip.volumeM3(),
                s.formulaMinutes().setScale(2, RoundingMode.HALF_UP),
                s.departure(),
                FuelLedger.tripLitres(trip, d, vehicle),
                trip.hasFixedSequence()));
        StopTime previous = null;
        for (StopTime stop : s.stops()) {
          stops.put(stop.order().orderId(), new Stop(tripId, stop.sequence(), stop.arrival()));
          legs.add(
              previous == null
                  ? new LegRow(tripId, 1, Optional.empty(), stop.order().outletId(), s.departure(),
                      stop.arrival(), d.outboundMinutes())
                  : new LegRow(tripId, stop.sequence(), Optional.of(previous.order().outletId()),
                      stop.order().outletId(), clock(previous.serviceEndMinute()), stop.arrival(),
                      d.interStopMinutes()));
          previous = stop;
        }
      }
      fuel.add(new FuelRow(vehicle.vehicleId(), FuelLedger.dayLitres(day, travel)));
    }

    List<AllocationRow> allocations = new ArrayList<>();
    List<DeferralRow> deferrals = new ArrayList<>();
    for (OrderDecision decision : run.decisions()) {
      PlanOrder o = order(built, decision.orderId());
      Optional<Stop> stop = Optional.ofNullable(stops.get(o.orderId()));
      Optional<Mark> mark = run.markOf(o.orderId());
      String source = mark.map(m -> m.source().name().toLowerCase(java.util.Locale.ROOT)).orElse("engine");
      boolean locked = mark.map(Mark::locked).orElse(false);
      // A decision keeps the time it was first taken while it stands unchanged.
      AllocationRow before = predecessor.allocations().get(o.orderId());
      Optional<Instant> decidedAt =
          mark.map(
              m ->
                  before != null && before.source().equals(source) && before.decidedBy().equals(Optional.of(m.actor()))
                      ? before.decidedAt().orElse(at)
                      : at);
      allocations.add(
          new AllocationRow(
              o.orderId(),
              o.outletId(),
              decision.decision(),
              stop.map(Stop::tripId),
              stop.map(Stop::sequence),
              stop.map(Stop::arrival),
              o.windowOpen(),
              o.windowClose(),
              o.serviceMinutes(),
              decision.bindingRule(),
              decision.reason(),
              decision.checks(),
              source,
              locked,
              mark.map(Mark::actor),
              decidedAt));
      DeferralRow earlier = predecessor.deferrals().get(o.orderId());
      if (decision.decision() == AllocationDecision.DEFERRED
          && earlier != null
          && earlier.ruleId().equals(decision.bindingRule().orElse(""))
          && earlier.reason().equals(decision.reason())) {
        deferrals.add(earlier);
      } else if (decision.decision() == AllocationDecision.DEFERRED) {
        deferrals.add(
            new DeferralRow(
                o.orderId(),
                o.outletId(),
                run.serviceDate(),
                decision.bindingRule().orElseThrow(),
                decision.reason(),
                o.deferralCount() + 1,
                run.deferredBy().getOrDefault(o.orderId(), Actor.SYSTEM_ID),
                at));
      }
    }

    RunRow header =
        new RunRow(
            run.planId(),
            run.depotCode(),
            run.serviceDate(),
            run.planVersion(),
            run.status(),
            run.stamps().referenceVersionId(),
            run.stamps().ruleSetId(),
            run.stamps().policyVersionId(),
            run.supersedes(),
            run.revisionReason(),
            run.demandFingerprint(),
            run.stale(),
            run.partial(),
            run.engine(),
            run.improvement(),
            run.cost(),
            true,
            at,
            generatedBy,
            Optional.empty(),
            Optional.empty(),
            run.rowVersion());
    return new Rows(header, trips, allocations, legs, fuel, deferrals);
  }

  /**
   * A stored run, rebuilt against the problem at its stamped versions. The
   * caller has already checked the demand fingerprint, so every allocated order
   * is in the problem; one that is not is reported as changed demand.
   */
  static PlanningRun load(
      RunRow run, List<TripRow> trips, List<AllocationRow> allocations, List<DeferralRow> deferrals, Built built) {
    return load(run, trips, allocations, deferrals, built, false);
  }

  /**
   * @param dropMissing true for a revision: an order no longer in the demand
   *     (cancelled since publication) leaves its trip instead of refusing
   */
  static PlanningRun load(
      RunRow run,
      List<TripRow> trips,
      List<AllocationRow> allocations,
      List<DeferralRow> deferrals,
      Built built,
      boolean dropMissing) {
    if (dropMissing) {
      allocations = allocations.stream().filter(a -> built.orders().containsKey(a.orderId())).toList();
      deferrals = deferrals.stream().filter(d -> built.orders().containsKey(d.orderId())).toList();
    }
    Map<UUID, TripRow> tripById = trips.stream().collect(Collectors.toMap(TripRow::tripId, t -> t));
    Map<UUID, List<PlanOrder>> onTrip = new HashMap<>();
    for (AllocationRow a : allocations) {
      a.tripId().ifPresent(t -> onTrip.computeIfAbsent(t, k -> new ArrayList<>()).add(order(built, a.orderId())));
    }

    Map<String, List<TripRow>> byVehicle =
        trips.stream().collect(Collectors.groupingBy(TripRow::vehicleId));
    List<VehicleDay> days = new ArrayList<>();
    for (Map.Entry<String, List<TripRow>> e : byVehicle.entrySet()) {
      if (e.getValue().stream().allMatch(t -> onTrip.getOrDefault(t.tripId(), List.of()).isEmpty())) {
        continue;
      }
      FleetVehicle vehicle = built.fleet().get(e.getKey());
      if (vehicle == null) {
        throw new IllegalStateException(
            "vehicle " + e.getKey() + " is not in reference version " + run.referenceVersionId());
      }
      List<Trip> vehicleTrips =
          e.getValue().stream()
              .sorted(Comparator.comparingInt(TripRow::tripNumber))
              .filter(t -> !onTrip.getOrDefault(t.tripId(), List.of()).isEmpty())
              .map(
                  t ->
                      tripOf(t, onTrip.getOrDefault(t.tripId(), List.of())))
              .toList();
      days.add(new VehicleDay(vehicle, vehicleTrips));
    }

    List<OrderDecision> decisions = new ArrayList<>();
    for (AllocationRow a : allocations) {
      Optional<TripRow> trip = a.tripId().map(tripById::get);
      decisions.add(
          new OrderDecision(
              a.orderId(),
              a.decision(),
              trip.map(TripRow::vehicleId),
              trip.map(TripRow::tripNumber),
              a.bindingRule(),
              a.reason(),
              a.checks()));
    }
    Map<UUID, UUID> deferredBy =
        deferrals.stream().collect(Collectors.toMap(DeferralRow::orderId, DeferralRow::actorId));
    Map<UUID, Mark> marks = new HashMap<>();
    for (AllocationRow a : allocations) {
      if (a.decidedBy().isPresent()) {
        marks.put(
            a.orderId(),
            new Mark(Source.valueOf(a.source().toUpperCase(java.util.Locale.ROOT)), a.decidedBy().get(), a.locked()));
      }
    }

    return new PlanningRun(
        run.planId(),
        run.depotCode(),
        run.serviceDate(),
        run.planVersion(),
        run.status(),
        new Stamps(run.referenceVersionId(), run.ruleSetId(), run.priorityPolicyVersionId()),
        run.supersedes(),
        run.revisionReason(),
        run.demandFingerprint(),
        run.stale(),
        run.partial(),
        run.engine(),
        run.improvement(),
        run.cost(),
        days,
        decisions,
        deferredBy,
        marks,
        run.rowVersion());
  }

  /** Trips and stops as Loading and Execution build their own records from them. */
  static List<PlannedTrip> plannedTrips(List<TripRow> trips, List<AllocationRow> allocations) {
    Map<UUID, List<PlannedStop>> stops = new HashMap<>();
    for (AllocationRow a : allocations) {
      if (a.tripId().isPresent()) {
        stops
            .computeIfAbsent(a.tripId().get(), k -> new ArrayList<>())
            .add(
                new PlannedStop(
                    a.stopSequence().orElseThrow(), a.orderId(), a.outletId(), a.plannedArrival().orElseThrow()));
      }
    }
    return trips.stream()
        .map(
            t ->
                new PlannedTrip(
                    t.tripId(),
                    t.vehicleId(),
                    t.tripNumber(),
                    t.brandCode(),
                    t.districtName(),
                    t.temperature(),
                    t.plannedDeparture(),
                    stops.getOrDefault(t.tripId(), List.of()).stream()
                        .sorted(Comparator.comparingInt(PlannedStop::sequence))
                        .toList()))
        .toList();
  }

  /** A stored trip as the domain holds it; a fixed stop order is read back from the stored sequence. */
  private static Trip tripOf(TripRow t, List<PlanOrder> inStopOrder) {
    Trip trip = new Trip(t.brandCode(), t.districtName(), TemperatureClass.of(t.temperature()), inStopOrder);
    return t.manualSequence() ? trip.withSequence(inStopOrder.stream().map(PlanOrder::orderId).toList()) : trip;
  }

  private record Stop(UUID tripId, int sequence, LocalTime arrival) {}

  private static PlanOrder order(Built built, UUID orderId) {
    PlanOrder o = built.orders().get(orderId);
    if (o == null) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "order " + orderId + " is no longer in the demand; generate the plan again",
          List.of("PLN-07"));
    }
    return o;
  }

  /** A minute of the day as a clock time; past midnight is held at 23:59, as the timeline does. */
  private static LocalTime clock(BigDecimal minuteOfDay) {
    long seconds = minuteOfDay.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).longValue();
    return seconds >= 86_400 ? LocalTime.of(23, 59) : LocalTime.ofSecondOfDay(Math.max(0, seconds));
  }
}
