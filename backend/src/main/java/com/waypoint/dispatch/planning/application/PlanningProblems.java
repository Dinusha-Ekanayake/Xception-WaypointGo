package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.referencedata.contract.ReferenceViews;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.contract.OrderViews.DemandView;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Problem;
import com.waypoint.dispatch.planning.domain.DemandFingerprint;
import com.waypoint.dispatch.planning.domain.Coordinates;
import com.waypoint.dispatch.planning.domain.DistrictTravel;
import com.waypoint.dispatch.planning.domain.FleetVehicle;
import com.waypoint.dispatch.planning.domain.PlanOrder;
import com.waypoint.dispatch.planning.domain.PriorityPolicy;
import com.waypoint.dispatch.planning.domain.RuleSet;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.TravelView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Assembles one depot-day as a planning problem, from contracts only: demand
 * from Ordering, outlets, fleet, travel and allowances from reference data at a
 * named version, fuel and service history from Planning's own tables.
 *
 * <p>Runs inside a Planning command's transaction. Capacity reads the order's
 * own weight and volume, never product lines (AGENTS.md, catalogue rule 1), and
 * temperature is the order's (rule 2).
 */
@Component
public class PlanningProblems {
  private final OrderQuery orders;
  private final ReferenceQuery reference;
  private final ReferenceSnapshotCache cache;
  private final JdbcPlanRepository plans;

  public PlanningProblems(
      OrderQuery orders, ReferenceQuery reference, JdbcPlanRepository plans, ReferenceSnapshotCache cache) {
    this.cache = cache;
    this.orders = orders;
    this.reference = reference;
    this.plans = plans;
  }

  /**
   * @param fingerprint of the demand as it is now, for the publication gate
   * @param orders by id, including those the engine will not serve
   */
  public record Built(Problem problem, String fingerprint, Map<UUID, PlanOrder> orders, Map<String, FleetVehicle> fleet) {}

  public Built build(
      String depotCode,
      LocalDate serviceDate,
      UUID referenceVersionId,
      RuleSet rules,
      PriorityPolicy policy,
      Optional<UUID> fuelExcluding) {
    return build(depotCode, serviceDate, referenceVersionId, rules, policy, fuelExcluding, Set.of());
  }

  /**
   * @param fuelExcluding a published run the result will supersede: its litres
   *     are replaced, not added to
   * @param carried orders a revision inherits from the plan it supersedes. Once
   *     published they are no longer confirmed demand, so each is read by id;
   *     one cancelled since is left out, and the revision drops it
   */
  public Built build(
      String depotCode,
      LocalDate serviceDate,
      UUID referenceVersionId,
      RuleSet rules,
      PriorityPolicy policy,
      Optional<UUID> fuelExcluding,
      Set<UUID> carried) {
    List<DemandView> demand = new ArrayList<>(orders.confirmedDemand(depotCode, serviceDate));
    Set<UUID> confirmed = demand.stream().map(DemandView::orderId).collect(Collectors.toSet());
    for (UUID id : carried.stream().sorted().toList()) {
      if (!confirmed.contains(id)) {
        orders.order(id).flatMap(PlanningProblems::asDemand).ifPresent(demand::add);
      }
    }
    Map<String, LocalDate> lastServed = plans.lastServed(depotCode, serviceDate);

    Map<UUID, PlanOrder> byId = new LinkedHashMap<>();
    Map<UUID, Long> versions = new HashMap<>();
    for (DemandView d : demand) {
      byId.put(d.orderId(), toPlanOrder(d, depotCode, serviceDate, referenceVersionId, lastServed));
      versions.put(d.orderId(), d.rowVersion());
    }

    Map<String, DistrictTravel> travel = new HashMap<>();
    Set<String> districts = demand.stream().map(DemandView::districtName).collect(Collectors.toSet());
    for (String district : districts) {
      cache.travelProfile(district, referenceVersionId).map(PlanningProblems::toTravel)
          .ifPresent(t -> travel.put(district, t));
    }

    Set<String> available =
        reference.availableVehicles(depotCode, serviceDate, referenceVersionId).stream()
            .map(VehicleView::vehicleId)
            .collect(Collectors.toSet());
    Map<String, FleetVehicle> fleet = new LinkedHashMap<>();
    for (VehicleView v : cache.vehiclesOfDepot(depotCode, referenceVersionId)) {
      fleet.put(
          v.vehicleId(),
          new FleetVehicle(
              v.vehicleId(),
              v.depotCode(),
              v.van(),
              v.refrigerated(),
              v.weightCapKg(),
              v.volumeCapM3(),
              v.kmPerL(),
              v.weeklyFuelQuotaL(),
              available.contains(v.vehicleId()),
              plans.fuelUsed(v.vehicleId(), serviceDate, fuelExcluding)));
    }

    Problem problem =
        new Problem(
            depotCode, serviceDate, new ArrayList<>(byId.values()), new ArrayList<>(fleet.values()),
            travel, rules, policy);
    return new Built(problem, DemandFingerprint.of(versions), byId, fleet);
  }

  /** A carried order as demand, unless it was cancelled or never measured. */
  private static Optional<DemandView> asDemand(OrderView o) {
    if (o.status() == OrderStatus.CANCELLED || o.weightKg() == null || o.volumeM3() == null || o.temperature() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new DemandView(
            o.orderId(), o.orderRef(), o.outletId(), o.brandCode(), o.districtName(), o.temperature(),
            o.weightKg(), o.volumeM3(), o.itemCount(), o.requestedDate(), o.deferralCount(), o.rowVersion()));
  }

  private PlanOrder toPlanOrder(
      DemandView d, String depotCode, LocalDate serviceDate, UUID version, Map<String, LocalDate> lastServed) {
    OutletView outlet =
        cache
            .outlet(d.outletId(), version)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.CONSTRAINT_VIOLATED,
                        "outlet " + d.outletId() + " is not in reference version " + version,
                        List.of("PLN-14")));
    BigDecimal allowance =
        cache
            .serviceAllowance(d.brandCode(), outlet.dockType(), version)
            .orElseThrow(
                () ->
                    new DomainException(
                        ErrorCode.CONSTRAINT_VIOLATED,
                        "no service allowance for " + d.brandCode() + " at a " + outlet.dockType()
                            + " dock in reference version " + version,
                        List.of("R-PLN-08")))
            .minutes();
    // Never served by Planning counts as zero days: an outlet with no history
    // must not outrank one that has been waiting.
    int daysSince =
        Optional.ofNullable(lastServed.get(d.outletId()))
            .map(last -> (int) ChronoUnit.DAYS.between(last, serviceDate))
            .orElse(0);
    return new PlanOrder(
        d.orderId(),
        d.orderRef(),
        d.outletId(),
        depotCode,
        d.brandCode(),
        d.districtName(),
        d.temperature(),
        d.weightKg(),
        d.volumeM3(),
        outlet.dockType(),
        outlet.vanOnly(),
        "mall_dock".equals(outlet.parkingConstraint()),
        outlet.effectiveWindowOpen(),
        outlet.effectiveWindowClose(),
        allowance,
        d.deferralCount(),
        daysSince,
        d.originalRequestedDate(),
        exactPoint(outlet.location()));
  }

  /** Only an exact point orders stops by distance; a district centre says nothing about where the outlet is (R-PLN-40). */
  private static Optional<Coordinates> exactPoint(Optional<ReferenceViews.GeoPoint> location) {
    return location
        .filter(p -> "exact".equals(p.precision()))
        .map(p -> new Coordinates(p.latitude().doubleValue(), p.longitude().doubleValue()));
  }

  private static DistrictTravel toTravel(TravelView t) {
    return new DistrictTravel(
        t.districtName(),
        t.depotToDistrictFreeflowMin(),
        t.interStopFreeflowMin(),
        t.depotToDistrictKm(),
        t.interStopKm());
  }
}
