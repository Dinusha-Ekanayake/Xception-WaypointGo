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
 * <p>Advice only. It never moves an order. Whether the order fits the trip is
 * Planning's to say (R-ORD-14): the caller passes that answer in, built from
 * the store's usual order, because this order's measures are not known until
 * the warehouse reserves it (R-ORD-12). Only
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
    return suggest(brandCode, chosen, openDays, stopsByDay, d -> true);
  }

  /**
   * @param roomOn whether the order would join that day's trip rather than need
   *     its own (R-ORD-14); asked only of days that would otherwise be offered
   */
  public static List<Suggestion> suggest(
      String brandCode,
      LocalDate chosen,
      List<LocalDate> openDays,
      Map<LocalDate, Integer> stopsByDay,
      java.util.function.Predicate<LocalDate> roomOn) {
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
        .filter(s -> roomOn.test(s.date()))
        .limit(MAX_SUGGESTIONS)
        .toList();
  }

  /** The days offered, and whether each was checked for room on its trip (R-ORD-14). */
  public record Checked(List<Suggestion> days, boolean roomChecked) {}

  /**
   * The suggestion with the room check when it can be made (R-ORD-14), and on
   * bookings alone when it cannot: no usual order to size it ({@code joins}
   * empty, ORD-22), or the check failing on any day (ORD-23). A failure drops the
   * check for every day, so no day is filtered by a check the screen says it
   * did not make.
   *
   * @param joins whether the order joins that day's trip; may throw
   * @param onFailure told once when the check could not be made
   */
  public static Checked suggestChecked(
      String brandCode,
      LocalDate chosen,
      List<LocalDate> openDays,
      Map<LocalDate, Integer> stopsByDay,
      java.util.Optional<java.util.function.Predicate<LocalDate>> joins,
      Runnable onFailure) {
    if (joins.isEmpty()) {
      return new Checked(suggest(brandCode, chosen, openDays, stopsByDay), false);
    }
    try {
      return new Checked(suggest(brandCode, chosen, openDays, stopsByDay, joins.get()), true);
    } catch (RuntimeException e) {
      onFailure.run();
      return new Checked(suggest(brandCode, chosen, openDays, stopsByDay), false);
    }
  }
}
