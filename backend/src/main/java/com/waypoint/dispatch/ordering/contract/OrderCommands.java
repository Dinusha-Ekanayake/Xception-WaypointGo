package com.waypoint.dispatch.ordering.contract;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of Ordering's commands, sent through {@code POST /api/commands}.
 *
 * <p>The kind of each command is also its action in {@code iam.action_catalogue},
 * so one string names the handler and the permission. {@code expectedVersion}
 * travels on the command envelope, not in the payload.
 */
public final class OrderCommands {
  private OrderCommands() {}

  public static final String PLACE = "order:Place";
  public static final String AMEND = "order:Amend";
  public static final String CANCEL = "order:Cancel";
  public static final String CLOSE_FOR_DAY = "order:CloseForDay";

  /** A product and how many of it. Sent to the warehouse; descriptive in Waypoint. */
  public record OrderLine(String productId, int quantity) {}

  /**
   * Placement calls the warehouse synchronously. A short line rejects the whole
   * order with the available quantities (D-F); an unreachable warehouse saves it
   * as {@code STOCK_UNKNOWN} (D-G).
   */
  public record PlaceOrder(String outletId, LocalDate requestedDate, List<OrderLine> lines) {

    public PlaceOrder {
      lines = List.copyOf(lines);
    }
  }

  /** Replaces the lines. The warehouse re-reserves; totals come back from it. */
  public record AmendOrder(UUID orderId, List<OrderLine> lines) {

    public AmendOrder {
      lines = List.copyOf(lines);
    }
  }

  /** The only command that releases the warehouse reservation (D-H). */
  public record CancelOrder(UUID orderId, String reason) {}

  /** At or after the 16:00 cutoff in Asia/Colombo, on the server clock (R-ORD-01, R-ORD-07). */
  public record CloseOrdersForDay(String depotCode, LocalDate serviceDate) {}
}
