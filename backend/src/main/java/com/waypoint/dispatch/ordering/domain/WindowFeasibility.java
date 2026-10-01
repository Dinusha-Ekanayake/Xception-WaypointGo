package com.waypoint.dispatch.ordering.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Optional;

/**
 * R-ORD-10: an outlet whose window is shorter than its service allowance can
 * never be served, so an order for it is rejected at capture, with the
 * arithmetic, rather than discovered at 04:00 by the planner (ORD-08).
 *
 * <p>The window is the effective one, the outlet window already intersected
 * with any mall window (R-PLN-29). An empty effective window is the extreme case.
 */
public final class WindowFeasibility {
  private WindowFeasibility() {}

  /** @return why the order can never be served, or empty when it can */
  public static Optional<String> violation(
      Optional<LocalTime> open, Optional<LocalTime> close, BigDecimal allowanceMinutes) {
    if (open.isEmpty() || close.isEmpty()) {
      return Optional.of("the outlet has no delivery window once its mall window is applied");
    }
    long windowMinutes = Duration.between(open.get(), close.get()).toMinutes();
    if (BigDecimal.valueOf(windowMinutes).compareTo(allowanceMinutes) < 0) {
      return Optional.of(
          "the delivery window "
              + open.get()
              + "-"
              + close.get()
              + " is "
              + windowMinutes
              + " min, shorter than the "
              + allowanceMinutes.stripTrailingZeros().toPlainString()
              + " min service allowance");
    }
    return Optional.empty();
  }
}
