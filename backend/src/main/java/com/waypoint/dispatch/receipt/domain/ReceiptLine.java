package com.waypoint.dispatch.receipt.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Optional;

/**
 * One product on a receipt: what the order said would arrive and what the store
 * says did (decision D-E). The expected quantity is descriptive; capacity was
 * decided on order totals and never on these lines.
 *
 * @param receivedQuantity empty until the store answers
 */
public record ReceiptLine(String productId, int expectedQuantity, Optional<Integer> receivedQuantity) {

  public ReceiptLine {
    if (productId == null || productId.isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "a receipt line needs a product");
    }
    if (expectedQuantity <= 0) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "an expected quantity must be positive");
    }
    if (receivedQuantity.isPresent() && receivedQuantity.get() < 0) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "a received quantity cannot be negative", List.of("R-RCP-01"));
    }
  }

  public static ReceiptLine expected(String productId, int quantity) {
    return new ReceiptLine(productId, quantity, Optional.empty());
  }

  public ReceiptLine received(int quantity) {
    return new ReceiptLine(productId, expectedQuantity, Optional.of(quantity));
  }

  public ReceiptLine receivedInFull() {
    return received(expectedQuantity);
  }

  /** Units the store says did not arrive; zero when complete or not yet answered. */
  public int shortBy() {
    return receivedQuantity.map(r -> Math.max(0, expectedQuantity - r)).orElse(0);
  }
}
