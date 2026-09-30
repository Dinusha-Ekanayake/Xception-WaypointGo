package com.waypoint.dispatch.ordering.domain;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * R-ORD-06: chilled and ambient never share one order, because vehicle
 * eligibility is decided per order (ORD-03).
 *
 * <p>The store submits the two halves as two orders; nothing here splits or
 * merges one (R-ORD-02, ORD-11). A product whose temperature the catalogue does
 * not yet publish (decision D-M) is not evidence either way, and the warehouse's
 * own answer is checked again by {@link Reservation}.
 */
public final class TemperatureMix {
  private TemperatureMix() {}

  /** @param byProduct each line's product and its catalogue temperature, if known */
  public static Optional<String> violation(Map<String, Optional<String>> byProduct) {
    Map<String, TreeSet<String>> products = new TreeMap<>();
    byProduct.forEach(
        (product, temperature) ->
            temperature.ifPresent(t -> products.computeIfAbsent(t, k -> new TreeSet<>()).add(product)));
    if (products.size() <= 1) {
      return Optional.empty();
    }
    return Optional.of(
        "chilled and ambient products cannot share one order; submit them as two orders: "
            + products);
  }
}
