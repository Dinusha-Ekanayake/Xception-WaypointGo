package com.waypoint.dispatch.referencedata.domain;

import java.util.Optional;

/**
 * A store that receives deliveries.
 *
 * @param mallWindow the mall's fixed access window, present only for a
 *     {@code MALL_DOCK} outlet
 */
public record Outlet(
    String id,
    String brandCode,
    String districtName,
    DockType dockType,
    ParkingConstraint parkingConstraint,
    DeliveryWindow window,
    Optional<DeliveryWindow> mallWindow,
    Optional<GeoPoint> location) {

  public Outlet(String id, String brandCode, String districtName, DockType dockType,
      ParkingConstraint parkingConstraint, DeliveryWindow window, Optional<DeliveryWindow> mallWindow) {
    this(id, brandCode, districtName, dockType, parkingConstraint, window, mallWindow, Optional.empty());
  }

  /**
   * R-PLN-29: what the outlet will actually accept. For a mall outlet that is
   * the overlap of its own window and the mall's; empty means the outlet cannot
   * be served at all, which is surfaced rather than silently attempted.
   */
  public Optional<DeliveryWindow> effectiveWindow() {
    return mallWindow.map(window::intersect).orElse(Optional.of(window));
  }

  /** R-PLN-03. */
  public boolean requiresVan() {
    return parkingConstraint == ParkingConstraint.VAN_ONLY;
  }
}
