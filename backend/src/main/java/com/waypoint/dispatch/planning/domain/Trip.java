package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One trip: one brand, one district, one temperature class (R-PLN-01, R-PLN-31).
 * Holds its orders unsequenced; {@link TripTimeline} decides the stop order,
 * because the order depends on when the trip leaves.
 *
 * <p>The brand, district and class are set by the first order and never
 * inferred again, so a constraint can see that a later order disagrees.
 */
public record Trip(String brand, String district, TemperatureClass temperature, List<PlanOrder> orders) {
  static final Comparator<PlanOrder> STABLE =
      Comparator.comparing(PlanOrder::orderRef).thenComparing(PlanOrder::orderId);

  public Trip {
    orders = orders.stream().sorted(STABLE).toList();
  }

  public static Trip of(PlanOrder first) {
    return new Trip(first.brand(), first.district(), first.temperatureClass(), List.of(first));
  }

  public Trip with(PlanOrder order) {
    List<PlanOrder> next = new ArrayList<>(orders);
    next.add(order);
    return new Trip(brand, district, temperature, next);
  }

  public Trip without(UUID orderId) {
    return new Trip(
        brand, district, temperature, orders.stream().filter(o -> !o.orderId().equals(orderId)).toList());
  }

  public boolean contains(UUID orderId) {
    return orders.stream().anyMatch(o -> o.orderId().equals(orderId));
  }

  public boolean fresh() {
    return "Fresh".equalsIgnoreCase(brand);
  }

  public BigDecimal weightKg() {
    return orders.stream().map(PlanOrder::weightKg).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public BigDecimal volumeM3() {
    return orders.stream().map(PlanOrder::volumeM3).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Whether an order matches this trip's brand, district and temperature class. */
  public boolean accepts(PlanOrder order) {
    return brand.equals(order.brand())
        && district.equals(order.district())
        && temperature == order.temperatureClass();
  }
}
