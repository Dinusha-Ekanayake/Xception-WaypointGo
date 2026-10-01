package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.DemandView;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.loading.infrastructure.JdbcManifestWriter;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A stand-in for Planning (#9) so the loader can be demonstrated before plans
 * are published. Never used once Planning exists.
 *
 * <p>Groups a depot-day's confirmed orders into trips that obey the rules a
 * loader would see broken: one brand, one district and one temperature per
 * trip, a refrigerated vehicle for chilled goods, a van for van-only outlets, order-level weight and volume
 * within the vehicle's caps, at most two trips per vehicle. It does not optimise
 * and does not check windows or fuel; that is Planning's job.
 *
 * <p>Builds Loading's manifests directly, because no relay delivers events yet
 * (#6), and also writes plan.published to the outbox so Ordering allocates the
 * orders once the relay runs. Loading ignores the redelivery: a trip version is
 * built once.
 */
@Component
public class LoadingFixture {
  private final Database database;
  private final OrderQuery orders;
  private final ReferenceQuery reference;
  private final EventPublisher events;
  private final JdbcManifestWriter guard;
  private final ManifestBuilder manifests;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public LoadingFixture(
      Database database,
      OrderQuery orders,
      ReferenceQuery reference,
      EventPublisher events,
      JdbcManifestWriter guard,
      ManifestBuilder manifests,
      Clock clock) {
    this.database = database;
    this.orders = orders;
    this.reference = reference;
    this.events = events;
    this.guard = guard;
    this.manifests = manifests;
    this.clock = clock;
  }

  /** @return the trips built */
  public int build(String depotCode, LocalDate serviceDate) {
    return database.asSystem(
        ModuleRole.LOADING,
        () -> {
          if (!guard.beginFixtureIfAbsent(depotCode, serviceDate)) {
            return 0;
          }
          List<DemandView> demand = orders.confirmedDemand(depotCode, serviceDate);
          List<PlannedTrip> trips = plan(depotCode, serviceDate, demand);
          if (trips.isEmpty()) {
            return 0;
          }
          UUID planId = UuidV7.generate(clock.now(), random);
          PlanPublished published = new PlanPublished(planId, depotCode, serviceDate, 1, Optional.empty(), trips);
          events.publish(Actor.SYSTEM, published);
          manifests.build(planId, Optional.empty(), depotCode, serviceDate, 1, trips);
          return trips.size();
        });
  }

  private List<PlannedTrip> plan(String depotCode, LocalDate serviceDate, List<DemandView> demand) {
    Map<String, List<DemandView>> groups = new LinkedHashMap<>();
    for (DemandView d : demand) {
      groups.computeIfAbsent(d.brandCode() + "|" + d.districtName() + "|" + d.temperature(), k -> new ArrayList<>())
          .add(d);
    }
    List<VehicleView> fleet = reference.availableVehicles(depotCode, serviceDate, null);
    Map<String, Integer> tripsUsed = new HashMap<>();
    List<PlannedTrip> trips = new ArrayList<>();
    int departureSlot = 0;
    for (List<DemandView> group : groups.values()) {
      DemandView first = group.get(0);
      boolean chilled = "chilled".equals(first.temperature());
      List<DemandView> remaining = new ArrayList<>(group);
      remaining.sort(Comparator.comparing(DemandView::outletId));
      while (!remaining.isEmpty()) {
        Optional<VehicleView> vehicle =
            fleet.stream()
                .filter(v -> !chilled || v.refrigerated())
                .filter(v -> tripsUsed.getOrDefault(v.vehicleId(), 0) < 2)
                .filter(v -> remaining.stream().anyMatch(d -> v.van() || !vanOnly(d)))
                .max(Comparator.comparing(VehicleView::weightCapKg));
        if (vehicle.isEmpty()) {
          break; // the rest would be deferred; that decision is Planning's
        }
        List<DemandView> load =
            fill(vehicle.get(), remaining.stream().filter(d -> vehicle.get().van() || !vanOnly(d)).toList());
        if (load.isEmpty()) {
          break; // an order larger than any vehicle is unservable, Planning's call
        }
        remaining.removeAll(load);
        int tripNumber = tripsUsed.merge(vehicle.get().vehicleId(), 1, Integer::sum);
        LocalTime departs =
            "Fresh".equalsIgnoreCase(first.brandCode())
                ? LocalTime.of(3, 30).plusMinutes(10L * departureSlot++)
                : LocalTime.of(7, 0).plusMinutes(10L * departureSlot++);
        List<PlannedStop> stops = new ArrayList<>();
        int sequence = 1;
        for (DemandView d : load) {
          stops.add(new PlannedStop(sequence, d.orderId(), d.outletId(), departs.plusMinutes(30L * sequence)));
          sequence++;
        }
        trips.add(
            new PlannedTrip(
                UuidV7.generate(clock.now(), random),
                vehicle.get().vehicleId(), tripNumber, first.brandCode(),
                first.districtName(), first.temperature(), departs, stops));
      }
    }
    return trips;
  }

  private boolean vanOnly(DemandView d) {
    return reference.outlet(d.outletId(), null).map(o -> o.vanOnly()).orElse(false);
  }

  /** Orders that fit the vehicle by order-level weight and volume (R-ORD-12). */
  private static List<DemandView> fill(VehicleView vehicle, List<DemandView> candidates) {
    BigDecimal weight = BigDecimal.ZERO;
    BigDecimal volume = BigDecimal.ZERO;
    List<DemandView> load = new ArrayList<>();
    for (DemandView d : candidates) {
      BigDecimal w = weight.add(d.weightKg());
      BigDecimal v = volume.add(d.volumeM3());
      if (w.compareTo(vehicle.weightCapKg()) <= 0 && v.compareTo(vehicle.volumeCapM3()) <= 0) {
        load.add(d);
        weight = w;
        volume = v;
      }
    }
    return load;
  }

}
