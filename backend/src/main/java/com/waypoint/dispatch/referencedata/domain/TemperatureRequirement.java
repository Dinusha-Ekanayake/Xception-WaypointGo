package com.waypoint.dispatch.referencedata.domain;

/** What an order needs. Frozen is treated exactly as chilled (R-PLN-26). */
public enum TemperatureRequirement {
  AMBIENT,
  CHILLED,
  FROZEN;

  public static TemperatureRequirement parse(String value) {
    return valueOf(value.trim().toUpperCase());
  }

  public boolean needsRefrigeration() {
    return this != AMBIENT;
  }
}
