package com.waypoint.dispatch.ordering.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A product and a quantity. Descriptive only (R-ORD-12): the order's own weight,
 * volume and temperature come from the warehouse and are never recomputed from
 * lines, because the catalogue is a reconstruction accurate to about 1%.
 */
public record OrderLine(String productId, int quantity) {

  public OrderLine {
    if (productId == null || productId.isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "lines[].productId is required");
    }
    if (quantity <= 0) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "lines[].quantity must be positive for " + productId + ", not " + quantity,
          List.of("R-ORD-05"));
    }
    productId = productId.trim();
  }

  /**
   * The lines of a store's submission. At least one, because the warehouse
   * reserves by line; an order that exists with no lines (CAT-05) is one the
   * warehouse supplied totals for, never one a store typed in.
   */
  public static List<OrderLine> forSubmission(List<OrderLine> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "lines must name at least one product and quantity");
    }
    Set<String> seen = new HashSet<>();
    for (OrderLine line : lines) {
      if (!seen.add(line.productId())) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED,
            "lines name " + line.productId() + " twice; send one line with the total quantity");
      }
    }
    return List.copyOf(lines);
  }
}
