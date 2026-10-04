package com.waypoint.dispatch.referencedata.domain;

/**
 * What a vehicle can carry.
 *
 * <p>A reefer is a ceiling, not an exclusion: assumption A-01, confirmed in the
 * training data, is that refrigerated vehicles may also carry ambient goods.
 */
public enum TemperatureCapability {
  REEFER,
  AMBIENT;

  public static TemperatureCapability parse(String value) {
    return valueOf(value.trim().toUpperCase());
  }

  public String code() {
    return name().toLowerCase();
  }

  /** R-PLN-02 and R-PLN-26: chilled and frozen both require refrigeration. */
  public boolean canCarry(TemperatureRequirement requirement) {
    return this == REEFER || requirement == TemperatureRequirement.AMBIENT;
  }
}
