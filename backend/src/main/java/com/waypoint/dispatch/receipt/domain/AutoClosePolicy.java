package com.waypoint.dispatch.receipt.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * How long a store has to answer before silence is recorded as
 * {@code AUTO_CLOSED} (R-RCP-05, P-10). The window is stamped on the receipt
 * when it is created, so changing P-10 never moves a deadline already given.
 */
public record AutoClosePolicy(Duration window) {

  public AutoClosePolicy {
    if (window == null || window.isNegative() || window.isZero()) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED, "the auto-close window must be positive", List.of("R-RCP-05"));
    }
  }

  public Instant closesAt(Instant deliveredAt) {
    return deliveredAt.plus(window);
  }
}
