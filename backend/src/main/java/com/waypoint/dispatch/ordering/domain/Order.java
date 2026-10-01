package com.waypoint.dispatch.ordering.domain;

import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The order aggregate: one outlet's demand for one delivery date, and where it is.
 *
 * <p>Immutable. Every change returns a new value, and every status change goes
 * through {@link OrderStateMachine}, so no caller can move an order along an edge
 * the graph does not have. Persistence and the version guard are the
 * repository's; this decides only whether a change is allowed.
 *
 * @param originalRequestedDate the date first asked for, kept across deferrals so
 *     priority can see how long the outlet has waited
 * @param reservation empty until the warehouse has reserved stock; weight, volume
 *     and temperature are unknown until then (decision D-G)
 * @param tripId the trip of the plan that last allocated it, so loading and
 *     release can find the orders of a trip
 * @param redeliveryOf the original, when Issues asked for a redelivery
 */
public record Order(
    UUID orderId,
    String orderRef,
    String outletId,
    String depotCode,
    String brandCode,
    String districtName,
    LocalDate requestedDate,
    LocalDate originalRequestedDate,
    LocalDate deliveryDate,
    OrderStatus status,
    Optional<Reservation> reservation,
    Optional<UUID> redeliveryOf,
    Optional<UUID> tripId,
    int deferralCount,
    List<OrderLine> lines,
    long rowVersion) {

  public Order {
    lines = List.copyOf(lines);
    if (status != OrderStatus.STOCK_UNKNOWN
        && status != OrderStatus.CANCELLED
        && status != OrderStatus.DEFERRED
        && reservation.isEmpty()) {
      throw new IllegalStateException(
          "Order " + orderRef + " is " + status + " with no warehouse reservation (R-STK-05)");
    }
  }

  /** A new order. Confirmed when the warehouse reserved it, stock-unknown when it could not be asked. */
  public static Order place(
      UUID orderId,
      String orderRef,
      String outletId,
      String depotCode,
      String brandCode,
      String districtName,
      DeliveryDate date,
      Optional<Reservation> reservation,
      List<OrderLine> lines) {
    return new Order(
        orderId,
        orderRef,
        outletId,
        depotCode,
        brandCode,
        districtName,
        date.requested(),
        date.requested(),
        date.delivery(),
        reservation.isPresent() ? OrderStatus.CONFIRMED : OrderStatus.STOCK_UNKNOWN,
        reservation,
        Optional.empty(),
        Optional.empty(),
        0,
        OrderLine.forSubmission(lines),
        0);
  }

  /**
   * A new order for goods that did not arrive. It carries the original's
   * reservation and measures: the goods are the same goods, still reserved at
   * the warehouse, so asking again would reserve them twice.
   */
  public static Order redeliveryOf(
      Order original, UUID orderId, String orderRef, LocalDate requested, LocalDate delivery) {
    if (original.reservation().isEmpty()) {
      throw new DomainException(
          ErrorCode.CONFLICT, "Order " + original.orderRef() + " has no reservation to redeliver");
    }
    return new Order(
        orderId,
        orderRef,
        original.outletId(),
        original.depotCode(),
        original.brandCode(),
        original.districtName(),
        requested,
        original.originalRequestedDate(),
        delivery,
        OrderStatus.CONFIRMED,
        original.reservation(),
        Optional.of(original.orderId()),
        Optional.empty(),
        0,
        original.lines(),
        0);
  }

  /** ORD-05 and ORD-06: amending is for orders Planning has not taken yet. */
  public Order amend(List<OrderLine> newLines, Optional<Reservation> newReservation) {
    requireAmendable();
    OrderStatus next = status;
    Optional<Reservation> kept = reservation;
    if (newReservation.isPresent()) {
      kept = newReservation;
      if (status == OrderStatus.STOCK_UNKNOWN) {
        next = OrderStatus.CONFIRMED;
      }
    }
    return copy(next, kept, tripId, deliveryDate, deferralCount, OrderLine.forSubmission(newLines));
  }

  public void requireAmendable() {
    if (OrderStateMachine.AMENDABLE.contains(status)) {
      return;
    }
    if (status == OrderStatus.CANCELLED) {
      throw new DomainException(ErrorCode.CONFLICT, "Order " + orderRef + " is cancelled");
    }
    if (OrderStateMachine.LOADING_OR_LATER.contains(status)) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "Order " + orderRef + " is " + status + "; loading has started, so raise an issue instead",
          List.of("ORD-06"));
    }
    throw new DomainException(
        ErrorCode.CONFLICT,
        "Order " + orderRef + " is " + status + "; a dispatcher must revalidate it before it changes",
        List.of("ORD-05"));
  }

  /** ORD-10: refused once loading has started. There is no returns workflow (A-10). */
  public Order cancel() {
    if (!OrderStateMachine.CANCELLABLE.contains(status)) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "Order " + orderRef + " is " + status + " and can no longer be cancelled; raise an issue",
          List.of("ORD-10"));
    }
    return moveTo(OrderStatus.CANCELLED);
  }

  /** The warehouse reserved a stock-unknown order after all (D-G). */
  public Order reserved(Reservation found) {
    if (status == OrderStatus.STOCK_UNKNOWN) {
      OrderStateMachine.require(status, OrderStatus.CONFIRMED);
      return copy(OrderStatus.CONFIRMED, Optional.of(found), tripId, deliveryDate, deferralCount, lines);
    }
    // Auto-deferred at the cutoff while stock was unknown: it stays deferred,
    // and now counts as demand because it is reserved.
    return copy(status, Optional.of(found), tripId, deliveryDate, deferralCount, lines);
  }

  /** Carried to a later run. The reservation is kept (D-H). */
  public Order deferTo(LocalDate nextDelivery, int skipCount) {
    OrderStateMachine.require(status, OrderStatus.DEFERRED);
    return copy(
        OrderStatus.DEFERRED,
        reservation,
        Optional.empty(),
        nextDelivery,
        Math.max(skipCount, deferralCount + 1),
        lines);
  }

  public Order allocateTo(UUID trip) {
    return copy(OrderStatus.ALLOCATED, reservation, Optional.of(trip), deliveryDate, deferralCount, lines);
  }

  /** A status change driven by a consumed event, already judged by {@link OrderStateMachine#onEvent}. */
  public Order moveTo(OrderStatus to) {
    return copy(to, reservation, tripId, deliveryDate, deferralCount, lines);
  }

  public Order withVersion(long version) {
    return new Order(
        orderId, orderRef, outletId, depotCode, brandCode, districtName, requestedDate,
        originalRequestedDate, deliveryDate, status, reservation, redeliveryOf, tripId,
        deferralCount, lines, version);
  }

  private Order copy(
      OrderStatus next,
      Optional<Reservation> nextReservation,
      Optional<UUID> nextTrip,
      LocalDate nextDelivery,
      int nextDeferrals,
      List<OrderLine> nextLines) {
    return new Order(
        orderId, orderRef, outletId, depotCode, brandCode, districtName, requestedDate,
        originalRequestedDate, nextDelivery, next, nextReservation, redeliveryOf, nextTrip,
        nextDeferrals, nextLines, rowVersion);
  }
}
