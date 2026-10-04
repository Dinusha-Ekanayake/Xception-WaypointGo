package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.AllowanceView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.TravelView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Reference data as Planning reads it to build a problem, kept in memory by
 * reference version (planning v2). A published reference version never
 * changes, so an answer for a version is the answer for good: there is nothing
 * to invalidate and no stale read to fear. A new version is simply a new key.
 *
 * <p>Only what is fixed by the version is kept: outlets, allowances, travel
 * profiles and a depot's vehicles. Which vehicles are available on a day is
 * not, because the workshop changes it during the version's life.
 *
 * <p>Bounded: past {@link #MAX_ENTRIES} the cache starts again, which costs a
 * few reads and never a wrong one.
 */
@Component
public class ReferenceSnapshotCache {
  static final int MAX_ENTRIES = 50_000;

  private final ReferenceQuery reference;
  private final Metrics metrics;
  private final Map<String, Object> entries = new ConcurrentHashMap<>();

  public ReferenceSnapshotCache(ReferenceQuery reference, Metrics metrics) {
    this.reference = reference;
    this.metrics = metrics;
  }

  public Optional<OutletView> outlet(String outletId, UUID version) {
    return get("outlet|" + version + "|" + outletId, () -> reference.outlet(outletId, version));
  }

  public Optional<AllowanceView> serviceAllowance(String brand, String dock, UUID version) {
    return get("allowance|" + version + "|" + brand + "|" + dock, () -> reference.serviceAllowance(brand, dock, version));
  }

  public Optional<TravelView> travelProfile(String district, UUID version) {
    return get("travel|" + version + "|" + district, () -> reference.travelProfile(district, version));
  }

  public List<VehicleView> vehiclesOfDepot(String depot, UUID version) {
    return get("vehicles|" + version + "|" + depot, () -> List.copyOf(reference.vehiclesOfDepot(depot, version)));
  }

  @SuppressWarnings("unchecked")
  private <T> T get(String key, Supplier<T> read) {
    Object hit = entries.get(key);
    if (hit != null) {
      metrics.increment("waypoint.planning.reference_cache", "result", "hit");
      return (T) hit;
    }
    metrics.increment("waypoint.planning.reference_cache", "result", "miss");
    T value = read.get();
    if (entries.size() >= MAX_ENTRIES) {
      entries.clear();
    }
    entries.put(key, value);
    return value;
  }
}
