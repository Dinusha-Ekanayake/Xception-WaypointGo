package com.waypoint.dispatch.loading.contract;

import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestLineView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ReadyTripView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ShortfallView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The only way another module reads dock work. */
public interface LoadingQuery {

  /** Built from the current plan version only. */
  Optional<ManifestView> manifest(UUID tripId);

  /**
   * One order's line on a trip, with its item checks, from the newest plan version that carries it.
   * Readable by the depot and by the order's own outlet, which sees this line and nothing else of
   * the trip: the custody chain (R-RCP-08) and the store's count (Figma "06-5"). Its load sequence
   * is 0, because the rest of the trip is not read.
   */
  Optional<ManifestLineView> orderLine(UUID tripId, UUID orderId);

  List<ReadyTripView> readyTrips(String depotCode, LocalDate serviceDate);

  List<ShortfallView> openShortfalls(String depotCode);
}
