package com.waypoint.dispatch.referencedata.domain;

import java.math.BigDecimal;
import java.util.Set;

/** R-REF-02: a sourced reference point, never an inferred exact outlet. */
public record GeoPoint(BigDecimal latitude, BigDecimal longitude, String precision) {
  public GeoPoint {
    if (latitude == null || longitude == null
        || latitude.abs().compareTo(new BigDecimal("90")) > 0
        || longitude.abs().compareTo(new BigDecimal("180")) > 0
        || latitude.stripTrailingZeros().scale() > 6
        || longitude.stripTrailingZeros().scale() > 6
        || precision == null || !Set.of("exact", "approximate", "centroid", "district").contains(precision)) {
      throw new GeoReference.Invalid("invalid geographic point or precision");
    }
  }

  public GeoPoint forOutlet() {
    return new GeoPoint(latitude, longitude, "district");
  }
}
