package com.waypoint.dispatch.ordering.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules and the store see of an order.
 *
 * <p>Weight, volume and temperature are the order's own, returned by the
 * warehouse at placement, and are authoritative for capacity (R-ORD-12, AGENTS.md
 * External Product Catalogue). Lines are descriptive: no module may sum them to
 * decide whether a load fits.
 */
public final class OrderViews {
  private OrderViews() {}

  /**
   * @param orderRef the business identifier, unique, shown to people
   * @param requestedDate the date the store asked for
   * @param deliveryDate the date it will be served, after rolling past
   *     non-operating days (decision D-I)
   * @param dateRolled true when {@code deliveryDate} differs from
   *     {@code requestedDate}, so the UI can say why
   * @param temperature {@code chilled} or {@code ambient}, from the warehouse
   * @param warehouseOrderRef the warehouse's order id, which is the stock
   *     reservation. Empty while {@link OrderStatus#STOCK_UNKNOWN}
   * @param redeliveryOf the original order when this one is a redelivery
   */
  /**
   * @param plannedStop the order's stop on the published plan for its delivery
   *     day, while it is planned, loading or on the road (issue #224)
   * @param plannedArrival the planned arrival at that stop, depot time
   */
  public record OrderView(
      UUID orderId,
      String orderRef,
      String outletId,
      String depotCode,
      String brandCode,
      String districtName,
      LocalDate requestedDate,
      LocalDate deliveryDate,
      boolean dateRolled,
      String temperature,
      int itemCount,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      OrderStatus status,
      Optional<String> warehouseOrderRef,
      Optional<UUID> redeliveryOf,
      int deferralCount,
      Instant placedAt,
      List<OrderLineView> lines,
      long rowVersion,
      Optional<Integer> plannedStop,
      Optional<java.time.LocalTime> plannedArrival) {

    public OrderView {
      lines = List.copyOf(lines);
    }

    public OrderView(
        UUID orderId, String orderRef, String outletId, String depotCode, String brandCode, String districtName,
        LocalDate requestedDate, LocalDate deliveryDate, boolean dateRolled, String temperature, int itemCount,
        BigDecimal weightKg, BigDecimal volumeM3, OrderStatus status, Optional<String> warehouseOrderRef,
        Optional<UUID> redeliveryOf, int deferralCount, Instant placedAt, List<OrderLineView> lines, long rowVersion) {
      this(orderId, orderRef, outletId, depotCode, brandCode, districtName, requestedDate, deliveryDate, dateRolled,
          temperature, itemCount, weightKg, volumeM3, status, warehouseOrderRef, redeliveryOf, deferralCount, placedAt,
          lines, rowVersion, Optional.empty(), Optional.empty());
    }

    /** The same order with its stop on the published plan (issue #224). */
    public OrderView withStop(int stop, Optional<java.time.LocalTime> arrival) {
      return new OrderView(orderId, orderRef, outletId, depotCode, brandCode, districtName, requestedDate, deliveryDate,
          dateRolled, temperature, itemCount, weightKg, volumeM3, status, warehouseOrderRef, redeliveryOf,
          deferralCount, placedAt, lines, rowVersion, Optional.of(stop), arrival);
    }
  }

  /** Descriptive only. Never summed for capacity. */
  public record OrderLineView(String productId, int quantity) {}

  /** One entry of an order's timeline. Every change carries an actor and a reason (rule 8). */
  public record StatusChangeView(
      Optional<OrderStatus> from,
      OrderStatus to,
      String reason,
      Optional<UUID> actorId,
      Instant at) {}

  /**
   * What Planning allocates: order-level measures only.
   *
   * @param originalRequestedDate kept across deferrals so priority can see how
   *     long an outlet has waited
   * @param deferralCount how many runs have skipped it (R-PLN-20)
   */
  public record DemandView(
      UUID orderId,
      String orderRef,
      String outletId,
      String brandCode,
      String districtName,
      String temperature,
      BigDecimal weightKg,
      BigDecimal volumeM3,
      int itemCount,
      LocalDate originalRequestedDate,
      int deferralCount,
      long rowVersion) {}

  /** One day of placed demand for a depot and brand (issue #16). */
  public record DailyVolumeView(
      LocalDate date, int orders, java.math.BigDecimal totalM3, java.math.BigDecimal chilledM3) {}

  /** One brand's orders due on one day at a depot, by delivery date (issue #224). */
  public record BookedVolumeView(
      LocalDate date, String brandCode, int orders, java.math.BigDecimal totalM3, java.math.BigDecimal chilledM3) {}

  /**
   * Nearby open days whose trip already serves the outlet's district (R-ORD-13,
   * issue #199). Advice only; {@code offered} is false for brands held to their
   * day, so the screen can say why nothing is shown.
   *
   * @param deliveryDate where the chosen day lands after any roll
   */
  public record RideAlongView(
      LocalDate requestedDate, LocalDate deliveryDate, boolean offered, List<RideAlongDay> days) {}

  /** A day and how many other outlets of the same brand and district are booked for it. */
  public record RideAlongDay(LocalDate date, int stopsBooked) {}
}
