package com.waypoint.dispatch.referencedata.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Optional;

/**
 * When an outlet will receive goods.
 *
 * <p>Two rules live here because they are easy to get wrong elsewhere:
 *
 * <ul>
 *   <li>R-EXE-13: arriving early means waiting. Service starts at
 *       {@code max(arrival, open)}, never at arrival.
 *   <li>R-EXE-14: late means after {@code close}, not after the planned time. A
 *       stop can be later than planned and not late, or on time and late.
 * </ul>
 */
public record DeliveryWindow(LocalTime open, LocalTime close) {

  public DeliveryWindow {
    if (open == null || close == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "A delivery window needs both ends");
    }
    if (!open.isBefore(close)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Window opens at " + open + " and closes at " + close);
    }
  }

  public long durationMinutes() {
    return Duration.between(open, close).toMinutes();
  }

  /** R-EXE-13: when handling actually begins. */
  public LocalTime serviceStartsAt(LocalTime arrival) {
    return arrival.isBefore(open) ? open : arrival;
  }

  /** R-EXE-14: measured against the close, never against the plan. */
  public boolean isLate(LocalTime arrival) {
    return arrival.isAfter(close);
  }

  /**
   * R-PLN-29: a mall outlet must satisfy both its own window and the mall's
   * access window, so the effective window is their overlap. Empty when they do
   * not overlap at all, which makes the outlet unservable rather than silently
   * attempted.
   */
  public Optional<DeliveryWindow> intersect(DeliveryWindow other) {
    LocalTime start = open.isAfter(other.open) ? open : other.open;
    LocalTime end = close.isBefore(other.close) ? close : other.close;
    return start.isBefore(end) ? Optional.of(new DeliveryWindow(start, end)) : Optional.empty();
  }
}
