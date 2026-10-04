package com.waypoint.dispatch.referencedata.domain;

/** Physical access at the outlet. */
public enum ParkingConstraint {
  NORMAL,
  /** R-PLN-03: trucks cannot reach this outlet. */
  VAN_ONLY,
  /** Access is limited to the mall's own delivery window. */
  MALL_DOCK;

  public static ParkingConstraint parse(String value) {
    return valueOf(value.trim().toUpperCase());
  }

  public String code() {
    return name().toLowerCase();
  }
}
