package com.waypoint.dispatch.ordering.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Where an order lands, and why it moved.
 *
 * <p>Three rules can move a requested date, applied in this order:
 *
 * <ol>
 *   <li>{@code cutoff}: the date is earlier than the first run still open (R-ORD-01)
 *   <li>{@code closed}: the dispatcher already closed ordering for that depot-day
 *   <li>{@code non_operating}: the network does not run that day (R-ORD-08, D-I)
 * </ol>
 *
 * <p>The calendar and the closures are predicates rather than a repository, so
 * the rule is tested with no reference data and no database.
 */
public record DeliveryDate(LocalDate requested, LocalDate delivery, List<String> reasons) {
  /** Past this the calendar is wrong, not merely sparse; say so rather than loop. */
  static final int HORIZON_DAYS = 60;

  public DeliveryDate {
    reasons = List.copyOf(reasons);
  }

  public boolean rolled() {
    return !requested.equals(delivery);
  }

  public static DeliveryDate resolve(
      LocalDate requested,
      Instant now,
      Predicate<LocalDate> isOperating,
      Predicate<LocalDate> isClosed) {
    if (requested == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "requestedDate is required");
    }
    List<String> reasons = new ArrayList<>();
    LocalDate candidate = requested;
    LocalDate earliest = Cutoff.earliestServiceDate(now);
    if (candidate.isBefore(earliest)) {
      candidate = earliest;
      reasons.add("cutoff");
    }
    for (int i = 0; i <= HORIZON_DAYS; i++) {
      if (isClosed.test(candidate)) {
        addOnce(reasons, "closed");
      } else if (!isOperating.test(candidate)) {
        addOnce(reasons, "non_operating");
      } else {
        return new DeliveryDate(requested, candidate, reasons);
      }
      candidate = candidate.plusDays(1);
    }
    throw new DomainException(
        ErrorCode.VALIDATION_FAILED,
        "No open operating day within " + HORIZON_DAYS + " days of " + requested,
        List.of("R-ORD-08"));
  }

  private static void addOnce(List<String> reasons, String reason) {
    if (!reasons.contains(reason)) {
      reasons.add(reason);
    }
  }
}
