package com.waypoint.dispatch.warehouse.domain;

import com.waypoint.dispatch.warehouse.contract.StockPort.Alternative;
import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.LineAvailability;
import com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Rejected;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import com.waypoint.dispatch.warehouse.contract.StockPort.Unavailable;
import com.waypoint.dispatch.warehouse.domain.WarehouseOrder.Shortfall;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Answered;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Failed;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Refused;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the warehouse's answer to {@code POST /orders} into Waypoint's terms.
 *
 * <ul>
 *   <li>{@code 201 pending}: reserved, with the warehouse's totals (R-STK-07)
 *   <li>{@code 202 reserved}: partly reserved and kept until the store decides
 *       (D-F revised 2026-10-01)
 *   <li>{@code 409}: nothing was available
 *   <li>{@code 404}, {@code 422}, {@code 400}: the request itself was refused
 *   <li>anything else: the outcome is unknown, never assumed reserved (R-STK-05)
 * </ul>
 *
 * <p>An answer Waypoint cannot plan, with no totals (STK-07) or a mixed
 * temperature (R-ORD-06), is refused here and the order it created is named for
 * release, so a reservation never outlives the decision not to keep it.
 */
public final class PlacementDecision {
  private PlacementDecision() {}

  static final Set<String> PLANNABLE_TEMPERATURES = Set.of("chilled", "ambient");

  /**
   * @param result what {@code StockPort} answers
   * @param release a warehouse order to cancel because it must not be kept
   * @param outcomeUnknown true when a write may or may not have happened
   */
  public record Outcome(PlacementResult result, Optional<String> release, boolean outcomeUnknown) {}

  public static Outcome decide(WarehouseReply reply, List<StockLine> requested) {
    if (reply instanceof Failed f) {
      return new Outcome(new Unavailable(f.reason()), Optional.empty(), f.sent());
    }
    if (reply instanceof Refused r) {
      return refused(r, requested);
    }
    return answered((Answered) reply);
  }

  private static Outcome refused(Refused r, List<StockLine> requested) {
    if (r.status() == 409) {
      return new Outcome(new Insufficient(availability(requested, r.shortfall())), Optional.empty(), false);
    }
    return new Outcome(
        new Rejected("warehouse refused the order: " + r.code() + ": " + r.message()),
        Optional.empty(),
        false);
  }

  private static Outcome answered(Answered a) {
    WarehouseOrder order = a.order();
    Optional<String> unusable = unusable(order);
    if (unusable.isPresent()) {
      return new Outcome(new Rejected(unusable.get()), Optional.of(order.orderId()), false);
    }
    Reserved reserved = reserved(order);
    if ("pending".equals(order.status())) {
      return new Outcome(reserved, Optional.empty(), false);
    }
    if ("reserved".equals(order.status()) && order.expiresAt().isPresent()) {
      List<LineAvailability> lines =
          order.items().stream()
              .map(i -> new LineAvailability(i.productId(), i.requestedQuantity(), i.quantity()))
              .toList();
      List<Alternative> alternatives =
          a.shortfall().stream()
              .filter(s -> s.otherWarehouse() != null)
              .map(s -> new Alternative(s.productId(), s.otherWarehouse(), s.otherAvailable()))
              .toList();
      return new Outcome(
          new PartiallyReserved(reserved, order.expiresAt().get(), lines, alternatives),
          Optional.empty(),
          false);
    }
    return new Outcome(
        new Rejected("warehouse answered a new order with status " + order.status()),
        Optional.of(order.orderId()),
        false);
  }

  /** STK-07 and R-ORD-06: a reply that could never be planned is not kept. */
  public static Optional<String> unusable(WarehouseOrder order) {
    if (order.totalWeightKg() == null || order.totalWeightKg().signum() <= 0
        || order.totalVolumeM3() == null || order.totalVolumeM3().signum() <= 0) {
      return Optional.of("warehouse order " + order.orderId() + " came back without usable totals");
    }
    if (!PLANNABLE_TEMPERATURES.contains(order.temperature())) {
      return Optional.of(
          "warehouse order " + order.orderId() + " is " + order.temperature()
              + "; an order carries one temperature class (R-ORD-06)");
    }
    if (order.units() <= 0) {
      return Optional.of("warehouse order " + order.orderId() + " holds no units");
    }
    return Optional.empty();
  }

  public static Reserved reserved(WarehouseOrder order) {
    return new Reserved(
        order.orderId(),
        order.totalWeightKg(),
        order.totalVolumeM3(),
        order.temperature(),
        order.units());
  }

  /** Per requested line; a line the warehouse did not mention had nothing available. */
  private static List<LineAvailability> availability(List<StockLine> requested, List<Shortfall> shortfall) {
    Map<String, Integer> reserved = new LinkedHashMap<>();
    for (Shortfall s : shortfall) {
      reserved.put(s.productId(), s.reserved());
    }
    return requested.stream()
        .map(l -> new LineAvailability(l.productId(), l.quantity(), reserved.getOrDefault(l.productId(), 0)))
        .toList();
  }
}
