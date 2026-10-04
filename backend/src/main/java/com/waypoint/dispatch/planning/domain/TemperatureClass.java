package com.waypoint.dispatch.planning.domain;

/** What a trip carries: refrigeration on or off (R-PLN-31, decision D-J). */
public enum TemperatureClass {
  CHILLED,
  AMBIENT;

  /** {@code frozen} needs a reefer exactly as {@code chilled} does (R-PLN-26). */
  public static TemperatureClass of(String temperature) {
    return switch (temperature.toLowerCase(java.util.Locale.ROOT)) {
      case "chilled", "frozen" -> CHILLED;
      default -> AMBIENT;
    };
  }

  public String code() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
