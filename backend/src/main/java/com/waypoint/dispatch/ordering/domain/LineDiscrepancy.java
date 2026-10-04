package com.waypoint.dispatch.ordering.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * CAT-02: product lines that sum to something other than the order total.
 *
 * <p>The order total wins, always. This only measures the gap so it can be
 * recorded; nothing adjusts the order to match its lines, because the catalogue
 * is a reconstruction and the order total is the warehouse's own figure.
 */
public final class LineDiscrepancy {
  private LineDiscrepancy() {}

  /** @return {@code |lines - order| / order}, or empty when the order total is not positive */
  public static Optional<BigDecimal> relative(BigDecimal orderTotal, BigDecimal linesTotal) {
    if (orderTotal == null || linesTotal == null || orderTotal.signum() <= 0) {
      return Optional.empty();
    }
    return Optional.of(
        linesTotal.subtract(orderTotal).abs().divide(orderTotal, MathContext.DECIMAL64));
  }
}
