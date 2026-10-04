package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The drive between two stops of one trip (R-PLN-40). When both outlets have an
 * exact point, the great-circle distance times {@link #ROAD_FACTOR}, driven at
 * the district's own inter-stop speed; otherwise the district's inter-stop
 * minutes and kilometres, exactly as the booklet gives them.
 *
 * <p>The booklet's trip-time formula for the 270 and 480 minute budgets does
 * not read this (R-PLN-08): GPS changes the order of stops and when each is
 * reached, never what a validator counts.
 */
public final class StopTravel {
  /** Roads are longer than straight lines; a common planning factor for mixed urban and rural roads. */
  public static final double ROAD_FACTOR = 1.3;

  private StopTravel() {}

  public static BigDecimal minutes(PlanOrder from, PlanOrder to, DistrictTravel d) {
    if (from.location().isEmpty() || to.location().isEmpty() || d.interStopKm().signum() == 0) {
      return d.interStopMinutes();
    }
    double km = roadKm(from, to);
    double minutesPerKm = d.interStopMinutes().doubleValue() / d.interStopKm().doubleValue();
    return BigDecimal.valueOf(km * minutesPerKm).setScale(4, RoundingMode.HALF_UP);
  }

  public static BigDecimal km(PlanOrder from, PlanOrder to, DistrictTravel d) {
    if (from.location().isEmpty() || to.location().isEmpty()) {
      return d.interStopKm();
    }
    return BigDecimal.valueOf(roadKm(from, to)).setScale(3, RoundingMode.HALF_UP);
  }

  /** Whether every stop has an exact point, so distance can order them. */
  public static boolean located(List<PlanOrder> orders) {
    return orders.size() > 2 && orders.stream().allMatch(o -> o.location().isPresent());
  }

  /**
   * The shortest open path through the stops by road kilometres, starting at
   * {@code first}: nearest neighbour, then 2-opt until nothing improves.
   * Deterministic: ties keep the given order.
   */
  public static List<PlanOrder> shortestPath(PlanOrder first, List<PlanOrder> orders) {
    List<PlanOrder> rest = new ArrayList<>(orders);
    rest.remove(first);
    List<PlanOrder> path = new ArrayList<>();
    path.add(first);
    while (!rest.isEmpty()) {
      PlanOrder last = path.get(path.size() - 1);
      PlanOrder nearest = rest.get(0);
      for (PlanOrder o : rest) {
        if (roadKm(last, o) < roadKm(last, nearest)) {
          nearest = o;
        }
      }
      path.add(nearest);
      rest.remove(nearest);
    }
    boolean improved = true;
    while (improved) {
      improved = false;
      for (int i = 1; i < path.size() - 1; i++) {
        for (int j = i + 1; j < path.size(); j++) {
          double before = roadKm(path.get(i - 1), path.get(i))
              + (j + 1 < path.size() ? roadKm(path.get(j), path.get(j + 1)) : 0);
          double after = roadKm(path.get(i - 1), path.get(j))
              + (j + 1 < path.size() ? roadKm(path.get(i), path.get(j + 1)) : 0);
          if (after + 1e-9 < before) {
            java.util.Collections.reverse(path.subList(i, j + 1));
            improved = true;
          }
        }
      }
    }
    return path;
  }

  private static double roadKm(PlanOrder a, PlanOrder b) {
    return a.location().orElseThrow().kmTo(b.location().orElseThrow()) * ROAD_FACTOR;
  }
}
