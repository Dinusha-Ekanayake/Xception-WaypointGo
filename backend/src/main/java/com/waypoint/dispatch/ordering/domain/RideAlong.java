package com.waypoint.dispatch.ordering.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Which nearby open days already carry other stops for the same brand and
 * district, so a store choosing a delivery day can join a trip that is going
 * there anyway (R-ORD-13, issue #199).
 *
 * <p>Advice only. It never moves an order and never decides whether an order
 * fits a vehicle: the order's weight and volume are not known until the
 * warehouse reserves it (R-ORD-12), and capacity stays with Planning. Only
 * Tech is offered a different day, because Fresh is daily and perishable and
 * Style is held to its scheduled day (R-ORD-03, R-ORD-11).
 */
public final class RideAlong {
  /** How many days either side of the chosen day are looked at. */
  public static final int REACH_DAYS = 2;

  /** At most this many days are offered, so the card stays a hint. */
  public static final int MAX_SUGGESTIONS = 2;

  public record Suggestion(LocalDate date, int stopsBooked) {}

  private RideAlong() {}

  public static boolean offeredTo(String brandCode) {
    return "Tech".equalsIgnoreCase(brandCode);
  }

  /**
   * @param brandCode the outlet's brand
   * @param chosen the day the order would be delivered on, after any roll
   * @param openDays the days within {@link #REACH_DAYS} that still take orders
   * @param stopsByDay other outlets of the same brand and district booked per day
   */
  public static List<Suggestion> suggest(
      String brandCode,
      LocalDate chosen,
      List<LocalDate> openDays,
      Map<LocalDate, Integer> stopsByDay) {
    if (!offeredTo(brandCode)) {
      return List.of();
    }
    int alreadyOnChosen = stopsByDay.getOrDefault(chosen, 0);
    return openDays.stream()
        .filter(d -> !d.equals(chosen))
        .filter(d -> Math.abs(ChronoUnit.DAYS.between(chosen, d)) <= REACH_DAYS)
        .map(d -> new Suggestion(d, stopsByDay.getOrDefault(d, 0)))
        .filter(s -> s.stopsBooked() > alreadyOnChosen)
        .sorted(
            Comparator.comparingInt(Suggestion::stopsBooked)
                .reversed()
                .thenComparingLong(s -> Math.abs(ChronoUnit.DAYS.between(chosen, s.date())))
                .thenComparing(Suggestion::date))
        .limit(MAX_SUGGESTIONS)
        .toList();
  }
}
