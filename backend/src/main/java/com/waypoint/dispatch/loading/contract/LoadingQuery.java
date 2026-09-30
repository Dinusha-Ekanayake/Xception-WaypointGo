package com.waypoint.dispatch.loading.contract;

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

  List<ReadyTripView> readyTrips(String depotCode, LocalDate serviceDate);

  List<ShortfallView> openShortfalls(String depotCode);
}
