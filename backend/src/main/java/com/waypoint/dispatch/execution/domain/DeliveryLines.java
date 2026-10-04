package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A delivery recorded product by product (decision 2026-10-01, issue #12), so a
 * dispute months later can say which product was short, not only how many units.
 *
 * <p>The order's product lines are descriptive and its unit count is the
 * warehouse's (D-E), so the two need not agree. When the lines add up to the
 * unit count, the delivered total follows from them; when they do not, the
 * driver's total is still needed and the lines are kept beside it as evidence.
 * Without lines, a delivery is recorded by its total alone, as before.
 *
 * <p>Pure: it checks and derives, and {@link DeliveryRecord#complete} still
 * decides the outcome with the total this returns.
 */
public final class DeliveryLines {
  private DeliveryLines() {}

  /** A product line of the order, as it was released. */
  public record Ordered(String productId, int units) {}

  /** What arrived of one product. */
  public record Delivered(String productId, int units) {}

  /** @param deliveredUnits the total to record; empty only when the caller gave neither lines nor a total */
  public record Settled(Optional<Integer> deliveredUnits, List<Delivered> lines) {
    public Settled {
      lines = List.copyOf(lines);
    }
  }

  /**
   * @param ordered the order's product lines
   * @param itemCount the order's unit count
   * @param outcome {@code DELIVERED} or {@code PARTIAL}
   * @param delivered what arrived of each product; empty to record a total only
   * @param total the driver's total, if they gave one
   */
  public static Settled settle(
      List<Ordered> ordered,
      int itemCount,
      DeliveryOutcome outcome,
      List<Delivered> delivered,
      Optional<Integer> total) {
    if (delivered.isEmpty()) {
      return new Settled(total, List.of());
    }
    if (ordered.isEmpty()) {
      throw invalid("This order has no product lines to record against; record the total delivered instead");
    }
    Map<String, Integer> orderedUnits = new HashMap<>();
    for (Ordered line : ordered) {
      orderedUnits.put(line.productId(), line.units());
    }

    Set<String> seen = new HashSet<>();
    int sum = 0;
    boolean shortLine = false;
    for (Delivered line : delivered) {
      Integer max = orderedUnits.get(line.productId());
      if (max == null) {
        throw invalid(line.productId() + " is not on this order");
      }
      if (!seen.add(line.productId())) {
        throw invalid(line.productId() + " is named twice; send one line per product");
      }
      if (line.units() < 0 || line.units() > max) {
        throw refused(line.productId() + " can be 0 to " + max + " units, not " + line.units());
      }
      shortLine |= line.units() < max;
      sum += line.units();
    }
    if (!seen.equals(orderedUnits.keySet())) {
      throw invalid("Say what arrived of every product on the order, including any that did not");
    }

    if (outcome == DeliveryOutcome.DELIVERED && shortLine) {
      throw refused("A full delivery has every unit of every product; record a short product as partial");
    }
    if (outcome == DeliveryOutcome.PARTIAL && (!shortLine || sum == 0)) {
      throw refused("A partial delivery has at least one product short and something delivered");
    }

    int orderedSum = orderedUnits.values().stream().mapToInt(Integer::intValue).sum();
    if (orderedSum != itemCount) {
      // The lines cannot give the total; the driver's count does, and the lines stand beside it.
      if (total.isEmpty()) {
        throw invalid("This order's products do not add up to its " + itemCount
            + " units, so say how many units were delivered as well");
      }
      return new Settled(total, delivered);
    }
    if (total.isPresent() && total.get() != sum) {
      throw refused("The products add up to " + sum + " units delivered, not " + total.get());
    }
    return new Settled(Optional.of(sum), delivered);
  }

  private static DomainException invalid(String message) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, message);
  }

  private static DomainException refused(String message) {
    return new DomainException(ErrorCode.CONSTRAINT_VIOLATED, message, List.of("R-EXE-01"));
  }
}
