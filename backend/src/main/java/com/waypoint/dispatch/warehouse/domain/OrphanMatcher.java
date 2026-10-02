package com.waypoint.dispatch.warehouse.domain;

import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the warehouse order an earlier, timed-out placement may have created.
 *
 * <p>The warehouse takes no idempotency key and stores no client reference, so
 * "query by our reference" (R-STK-11) has to be done by content: same
 * warehouse, exactly the same products and requested quantities, created inside
 * the window around one of our attempts, and not already claimed by another
 * Waypoint order. Anything other than exactly one candidate is not a match: two
 * is {@link Ambiguous}, and the caller must neither place nor adopt, because a
 * guess either double-reserves or steals another order's stock (STK-09).
 */
public final class OrphanMatcher {
  private OrphanMatcher() {}

  public sealed interface Match permits Found, NotFound, Ambiguous {}

  public record Found(WarehouseOrder order, int attempt) implements Match {}

  public record NotFound() implements Match {}

  public record Ambiguous(List<String> candidates) implements Match {

    public Ambiguous {
      candidates = List.copyOf(candidates);
    }
  }

  /** One {@code POST /orders} we sent whose answer we never saw. */
  public record Attempt(List<StockLine> lines, Instant sentAt) {

    public Attempt {
      lines = List.copyOf(lines);
    }
  }

  /**
   * @param candidates warehouse orders with their items, newest first
   * @param claimed warehouse refs already belonging to some Waypoint order
   * @param window how far either side of an attempt a creation time may fall;
   *     covers clock skew between us and the warehouse
   */
  public static Match match(
      String warehouseCode,
      List<Attempt> attempts,
      List<WarehouseOrder> candidates,
      Set<String> claimed,
      Duration window) {
    Found found = null;
    List<String> all = new java.util.ArrayList<>();
    for (WarehouseOrder candidate : candidates) {
      if (!warehouseCode.equals(candidate.warehouseCode()) || claimed.contains(candidate.orderId())) {
        continue;
      }
      if (!"reserved".equals(candidate.status()) && !"pending".equals(candidate.status())) {
        continue;
      }
      for (int i = 0; i < attempts.size(); i++) {
        Attempt attempt = attempts.get(i);
        if (within(candidate.createdAt(), attempt.sentAt(), window)
            && fingerprint(attempt.lines()).equals(requested(candidate))) {
          all.add(candidate.orderId());
          found = new Found(candidate, i);
          break;
        }
      }
    }
    if (all.isEmpty()) {
      return new NotFound();
    }
    if (all.size() > 1) {
      return new Ambiguous(all);
    }
    return found;
  }

  /** Product to quantity, merged and sorted, so line order and splitting do not matter. */
  public static Map<String, Integer> fingerprint(List<StockLine> lines) {
    Map<String, Integer> merged = new TreeMap<>();
    for (StockLine line : lines) {
      merged.merge(line.productId(), line.quantity(), Integer::sum);
    }
    return merged;
  }

  private static Map<String, Integer> requested(WarehouseOrder order) {
    Map<String, Integer> merged = new TreeMap<>();
    for (WarehouseOrder.Item item : order.items()) {
      merged.merge(item.productId(), item.requestedQuantity(), Integer::sum);
    }
    return merged;
  }

  private static boolean within(Instant created, Instant sent, Duration window) {
    return !created.isBefore(sent.minus(window)) && !created.isAfter(sent.plus(window));
  }
}
