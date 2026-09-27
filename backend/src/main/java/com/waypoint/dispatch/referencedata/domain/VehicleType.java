package com.waypoint.dispatch.referencedata.domain;

public enum VehicleType {
  TRUCK,
  VAN;

  public static VehicleType parse(String value) {
    return valueOf(value.trim().toUpperCase());
  }

  public String code() {
    return name().toLowerCase();
  }
}
