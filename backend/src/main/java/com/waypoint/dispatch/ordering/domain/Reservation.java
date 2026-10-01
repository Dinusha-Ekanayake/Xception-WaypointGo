package com.waypoint.dispatch.ordering.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * What the warehouse reserved, and the order's authoritative measures.
 *
 * <p>Weight, volume and temperature come from the warehouse's answer to
 * {@code POST /orders} and become the order's own (decision D-E). Capacity reads
 * these, never a sum of lines (R-ORD-12). A reservation that could never be
 * planned is refused here: a non-positive measure (ORD-07), or a temperature that
 * is not exactly one class (R-ORD-06), because vehicle eligibility is decided per
 * order.
 */
public record Reservation(
    String warehouseOrderRef,
    BigDecimal weightKg,
    BigDecimal volumeM3,
    String temperature,
    int itemCount) {

  public static final Set<String> TEMPERATURES = Set.of("chilled", "ambient");

  public Reservation {
    if (warehouseOrderRef == null || warehouseOrderRef.isBlank()) {
      throw invalid("the warehouse returned no order reference", "R-STK-08");
    }
    if (weightKg == null || weightKg.signum() <= 0) {
      throw invalid("weightKg must be positive, not " + weightKg, "R-ORD-05");
    }
    if (volumeM3 == null || volumeM3.signum() <= 0) {
      throw invalid("volumeM3 must be positive, not " + volumeM3, "R-ORD-05");
    }
    if (itemCount <= 0) {
      throw invalid("itemCount must be positive, not " + itemCount, "R-ORD-05");
    }
    if (temperature == null || !TEMPERATURES.contains(temperature)) {
      throw invalid(
          "temperature must be one of " + TEMPERATURES + ", not " + temperature, "R-ORD-06");
    }
  }

  private static DomainException invalid(String message, String rule) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, message, List.of(rule));
  }
}
