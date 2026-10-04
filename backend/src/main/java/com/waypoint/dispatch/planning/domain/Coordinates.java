package com.waypoint.dispatch.planning.domain;

/**
 * A point on the earth, in degrees (WGS 84). Only an outlet's exact point is
 * ever one of these in Planning; a district centre is not (R-PLN-40).
 */
public record Coordinates(double latitude, double longitude) {
  private static final double EARTH_RADIUS_KM = 6371.0088;

  public Coordinates {
    if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
      throw new IllegalArgumentException("not a point: " + latitude + ", " + longitude);
    }
  }

  /** Great-circle distance, the haversine formula. */
  public double kmTo(Coordinates other) {
    double dLat = Math.toRadians(other.latitude - latitude);
    double dLon = Math.toRadians(other.longitude - longitude);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
  }
}
