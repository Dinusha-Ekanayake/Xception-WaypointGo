package com.waypoint.dispatch.referencedata.domain;

/** How goods leave the vehicle. Drives the service allowance for a stop. */
public enum DockType {
  REAR_DOCK,
  STREET,
  MALL_BAY;

  public static DockType parse(String value) {
    return valueOf(value.trim().toUpperCase());
  }

  public String code() {
    return name().toLowerCase();
  }
}
