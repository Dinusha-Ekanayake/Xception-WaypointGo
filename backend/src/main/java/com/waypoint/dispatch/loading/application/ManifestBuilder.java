package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.loading.infrastructure.JdbcManifestWriter;
import com.waypoint.dispatch.loading.infrastructure.JdbcManifestWriter.StopRow;
import com.waypoint.dispatch.loading.infrastructure.JdbcManifestWriter.TripRow;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderLineView;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.config.LoadingProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds Loading's copy of a published or revised plan, one trip at a time.
 *
 * <p>Called by the plan consumers inside the relay's transaction, as the system
 * actor, so the contract reads into Ordering and Reference see every row. Each
 * order's measures come from Ordering's contract and are authoritative for
 * capacity (R-ORD-12); its lines become the item lines the loader ticks.
 *
 * <p>Safe to repeat: a trip version already built is skipped.
 */
@Component
public class ManifestBuilder {
  private final JdbcManifestWriter writer;
  private final OrderQuery orders;
  private final ReferenceQuery reference;
  private final LoadingProperties properties;
  private final Metrics metrics;
  private final Clock clock;

  public ManifestBuilder(
      JdbcManifestWriter writer,
      OrderQuery orders,
      ReferenceQuery reference,
      LoadingProperties properties,
      Metrics metrics,
      Clock clock) {
    this.writer = writer;
    this.orders = orders;
    this.reference = reference;
    this.properties = properties;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** @return trips built; a trip version already present is not rebuilt */
  public int build(
      UUID planId,
      Optional<UUID> supersedesPlanId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      List<PlannedTrip> trips) {
    Instant now = clock.now();
    List<PlannedTrip> byDeparture =
        trips.stream()
            .sorted(Comparator.comparing(PlannedTrip::plannedDeparture).thenComparing(t -> t.tripId().toString()))
            .toList();
    int built = 0;
    for (int i = 0; i < byDeparture.size(); i++) {
      PlannedTrip trip = byDeparture.get(i);
      if (writer.versionExists(trip.tripId(), planVersion)) {
        continue;
      }
      if (buildTrip(planId, depotCode, serviceDate, planVersion, trip, trips, dock(depotCode, i), now)) {
        built++;
      }
    }
    writer.retireOmittedTrips(planId, supersedesPlanId,
        byDeparture.stream().map(PlannedTrip::tripId).collect(java.util.stream.Collectors.toSet()), now);
    metrics.increment("waypoint.loading.manifests_built", "depot", depotCode);
    return built;
  }

  private boolean buildTrip(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      int planVersion,
      PlannedTrip trip,
      List<PlannedTrip> all,
      String dock,
      Instant now) {
    VehicleView vehicle =
        reference.vehicle(trip.vehicleId(), null)
            .orElseThrow(() -> new IllegalStateException("Plan names unknown vehicle " + trip.vehicleId()));
    int tripsForVehicle =
        (int) all.stream().filter(t -> t.vehicleId().equals(trip.vehicleId())).count();
    Optional<Integer> current = writer.currentVersion(trip.tripId());

    if (current.isPresent() && current.get() >= planVersion) {
      return false; // redelivery or an older version never changes current work
    }
    if (current.isPresent()) {
      if (!writer.supersede(trip.tripId(), current.get(), planVersion, now)) {
        return false; // a released trip stays the manifest the driver received
      }
    }
    writer.insertTrip(
        new TripRow(
            trip.tripId(), planVersion, planId, depotCode, serviceDate, trip.vehicleId(),
            trip.tripNumber(), Math.max(tripsForVehicle, trip.tripNumber()), trip.brandCode(),
            trip.districtName(), trip.temperature(), trip.plannedDeparture(), dock,
            vehicle.weightCapKg(), vehicle.volumeCapM3()),
        now);
    for (PlannedStop stop : trip.stops()) {
      OrderView order =
          orders.order(stop.orderId())
              .orElseThrow(() -> new IllegalStateException("Plan names unknown order " + stop.orderId()));
      if (order.weightKg() == null || order.volumeM3() == null || order.temperature() == null) {
        throw new IllegalStateException(
            "Order " + order.orderRef() + " has no warehouse measures and cannot be loaded (R-STK-05)");
      }
      writer.insertStop(
          trip.tripId(), planVersion,
          new StopRow(
              order.orderId(), stop.sequence(), order.orderRef(), order.outletId(), order.temperature(),
              order.itemCount(), order.weightKg(), order.volumeM3(), stop.plannedArrival()));
      int lineNo = 1;
      for (OrderLineView line : order.lines()) {
        writer.insertItem(trip.tripId(), planVersion, order.orderId(), lineNo++, line.productId(), line.quantity());
      }
      if (order.lines().isEmpty()) {
        // An order with no lines is still loaded, as one line of all its units.
        writer.insertItem(trip.tripId(), planVersion, order.orderId(), 1, "ORDER", order.itemCount());
      }
    }
    if (current.isPresent()) {
      writer.carryUnchangedChecks(trip.tripId(), current.get(), planVersion, now);
    } else {
      writer.insertSession(trip.tripId(), depotCode, planVersion, now);
    }
    return true;
  }

  private String dock(String depotCode, int index) {
    return "Dock " + (index % properties.docksFor(depotCode) + 1);
  }
}
