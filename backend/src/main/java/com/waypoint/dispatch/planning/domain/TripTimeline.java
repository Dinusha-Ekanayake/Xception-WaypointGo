package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * When each trip of a vehicle leaves and when each stop is reached.
 *
 * <p>Two clocks, deliberately different:
 *
 * <ul>
 *   <li>{@code formulaMinutes} is the booklet's trip time exactly: outbound +
 *       inter-stop x (stops - 1) + allowances, no return leg and no waiting
 *       (R-PLN-08). The time budgets read this, as the supplied validator does.
 *   <li>The stop clock adds waiting for a window to open, because an early
 *       vehicle waits (R-PLN-13). Delivery windows read this.
 * </ul>
 *
 * <p>The next trip leaves when the previous trip's last service ends, with no
 * return leg added, which is the model behind the booklet's 101 + 112 minute
 * example (assumption A-26). Stops are tried earliest-close first and
 * earliest-open first; the feasible order that finishes earlier wins, so the
 * result is deterministic (D-L: sequence by window; there are no coordinates, so
 * distance cannot reorder stops inside a district).
 */
public final class TripTimeline {
  private TripTimeline() {}

  public record StopTime(
      int sequence, PlanOrder order, BigDecimal arrivalMinute, BigDecimal waitMinutes, BigDecimal serviceEndMinute) {

    public LocalTime arrival() {
      return clock(arrivalMinute);
    }

    /** Arrived after the effective window closed, or the outlet has no window at all. */
    public boolean late() {
      if (!order.hasWindow()) {
        return true;
      }
      return arrivalMinute.compareTo(minuteOf(order.windowClose().get())) > 0;
    }

    /** Minutes between arrival and the window closing; negative when late. */
    public BigDecimal minutesToClose() {
      if (!order.hasWindow()) {
        return BigDecimal.ZERO;
      }
      return minuteOf(order.windowClose().get()).subtract(arrivalMinute);
    }
  }

  public record TripSchedule(
      int tripNumber,
      Trip trip,
      BigDecimal departureMinute,
      List<StopTime> stops,
      BigDecimal formulaMinutes,
      BigDecimal endMinute) {

    public TripSchedule {
      stops = List.copyOf(stops);
    }

    public LocalTime departure() {
      return clock(departureMinute);
    }

    public long lateStops() {
      return stops.stream().filter(StopTime::late).count();
    }
  }

  public static List<TripSchedule> schedule(
      VehicleDay day, Map<String, DistrictTravel> travel, RuleSet rules) {
    List<TripSchedule> out = new ArrayList<>();
    BigDecimal previousEnd = null;
    for (int i = 0; i < day.trips().size(); i++) {
      Trip trip = day.trips().get(i);
      DistrictTravel d = travelFor(travel, trip.district());
      BigDecimal base = minuteOf(trip.fresh() ? rules.freshDeparture() : rules.daytimeDeparture());
      BigDecimal earliest = previousEnd == null ? base : base.max(previousEnd);
      TripSchedule best = null;
      for (Comparator<PlanOrder> order : sequencesFor(trip)) {
        TripSchedule candidate = simulate(i + 1, trip, d, earliest, order);
        if (best == null || better(candidate, best)) {
          best = candidate;
        }
      }
      out.add(best);
      previousEnd = best.endMinute();
    }
    return out;
  }

  /** Booklet trip time: outbound, inter-stop x (stops - 1), and the allowances (R-PLN-08). */
  public static BigDecimal formulaMinutes(Trip trip, DistrictTravel d) {
    int n = trip.orders().size();
    if (n == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal service =
        trip.orders().stream().map(PlanOrder::serviceMinutes).reduce(BigDecimal.ZERO, BigDecimal::add);
    return d.outboundMinutes().add(d.interStopMinutes().multiply(BigDecimal.valueOf(n - 1L))).add(service);
  }

  static DistrictTravel travelFor(Map<String, DistrictTravel> travel, String district) {
    DistrictTravel d = travel.get(district);
    if (d == null) {
      throw new IllegalStateException("no travel profile for district " + district);
    }
    return d;
  }

  private static final Comparator<PlanOrder> CLOSE_FIRST =
      Comparator.comparing((PlanOrder o) -> o.windowClose().orElse(LocalTime.MIN))
          .thenComparing(o -> o.windowOpen().orElse(LocalTime.MIN))
          .thenComparing(Trip.STABLE);

  private static final Comparator<PlanOrder> OPEN_FIRST =
      Comparator.comparing((PlanOrder o) -> o.windowOpen().orElse(LocalTime.MIN))
          .thenComparing(o -> o.windowClose().orElse(LocalTime.MIN))
          .thenComparing(Trip.STABLE);

  private static final List<Comparator<PlanOrder>> SEQUENCES = List.of(CLOSE_FIRST, OPEN_FIRST);

  /** A dispatcher's stop order is the only one tried: it is the decision, checked like any other. */
  private static List<Comparator<PlanOrder>> sequencesFor(Trip trip) {
    if (!trip.hasFixedSequence()) {
      return SEQUENCES;
    }
    Map<java.util.UUID, Integer> position = new java.util.HashMap<>();
    for (int i = 0; i < trip.sequence().size(); i++) {
      position.put(trip.sequence().get(i), i);
    }
    return List.of(
        Comparator.comparingInt((PlanOrder o) -> position.getOrDefault(o.orderId(), Integer.MAX_VALUE))
            .thenComparing(Trip.STABLE));
  }

  private static TripSchedule simulate(
      int tripNumber, Trip trip, DistrictTravel d, BigDecimal earliest, Comparator<PlanOrder> sequence) {
    List<PlanOrder> ordered = trip.orders().stream().sorted(sequence).toList();
    BigDecimal departure = earliest;
    if (!ordered.isEmpty() && ordered.get(0).windowOpen().isPresent()) {
      BigDecimal leaveToArriveAtOpen = minuteOf(ordered.get(0).windowOpen().get()).subtract(d.outboundMinutes());
      departure = departure.max(leaveToArriveAtOpen);
    }
    List<StopTime> stops = new ArrayList<>();
    BigDecimal t = departure.add(d.outboundMinutes());
    for (int i = 0; i < ordered.size(); i++) {
      PlanOrder o = ordered.get(i);
      if (i > 0) {
        t = t.add(d.interStopMinutes());
      }
      BigDecimal arrival = t;
      BigDecimal wait = BigDecimal.ZERO;
      if (o.windowOpen().isPresent()) {
        wait = minuteOf(o.windowOpen().get()).subtract(arrival).max(BigDecimal.ZERO);
      }
      BigDecimal serviceEnd = arrival.add(wait).add(o.serviceMinutes());
      stops.add(new StopTime(i + 1, o, arrival, wait, serviceEnd));
      t = serviceEnd;
    }
    return new TripSchedule(tripNumber, trip, departure, stops, formulaMinutes(trip, d), t);
  }

  private static boolean better(TripSchedule a, TripSchedule b) {
    if (a.lateStops() != b.lateStops()) {
      return a.lateStops() < b.lateStops();
    }
    return a.endMinute().compareTo(b.endMinute()) < 0;
  }

  static BigDecimal minuteOf(LocalTime time) {
    return BigDecimal.valueOf(time.toSecondOfDay()).divide(BigDecimal.valueOf(60), 4, java.math.RoundingMode.HALF_UP);
  }

  /** A clock time for display; a minute past midnight is shown as 23:59 rather than wrapping. */
  static LocalTime clock(BigDecimal minuteOfDay) {
    long seconds = minuteOfDay.multiply(BigDecimal.valueOf(60)).setScale(0, java.math.RoundingMode.HALF_UP).longValue();
    if (seconds >= 86_400) {
      return LocalTime.of(23, 59);
    }
    return LocalTime.ofSecondOfDay(Math.max(0, seconds));
  }
}
