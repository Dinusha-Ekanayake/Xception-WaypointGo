package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryFailed;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingStarted;
import com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderDeferred;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderUnservable;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptAutoClosed;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptConfirmed;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptDisputed;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * The status moves Ordering takes from other modules' events, one subscriber
 * per event type. Each is a line of the state machine: the event names where
 * the order is now, and {@link OrderTransitions} decides whether that is news.
 */
final class OrderingConsumers {
  private OrderingConsumers() {}

  @Component
  static class OnPlanPublished extends OrderingConsumer<PlanPublished> {
    private final PlanAllocation allocation;

    OnPlanPublished(OrderTransitions transitions, PlanAllocation allocation) {
      super(transitions);
      this.allocation = allocation;
    }

    @Override
    public String consumerName() {
      return "ordering.on-plan-published";
    }

    @Override
    public Class<PlanPublished> eventType() {
      return PlanPublished.class;
    }

    @Override
    public void on(EventEnvelope<PlanPublished> envelope) {
      PlanPublished plan = envelope.payload();
      allocation.allocate(plan.trips(), plan.planVersion(), plan.serviceDate(), envelope);
    }
  }

  @Component
  static class OnPlanRevised extends OrderingConsumer<PlanRevised> {
    private final PlanAllocation allocation;

    OnPlanRevised(OrderTransitions transitions, PlanAllocation allocation) {
      super(transitions);
      this.allocation = allocation;
    }

    @Override
    public String consumerName() {
      return "ordering.on-plan-revised";
    }

    @Override
    public Class<PlanRevised> eventType() {
      return PlanRevised.class;
    }

    @Override
    public void on(EventEnvelope<PlanRevised> envelope) {
      PlanRevised plan = envelope.payload();
      allocation.allocate(plan.trips(), plan.planVersion(), plan.serviceDate(), envelope);
    }
  }

  /**
   * Carried to a later run, keeping the reservation (D-H, R-STK-14). The new
   * date is the next operating day after the run that skipped it; a repeat of
   * the same deferral finds the order already there.
   */
  @Component
  static class OnOrderDeferred extends OrderingConsumer<OrderDeferred> {
    private final JdbcOrderRepository orders;
    private final ReferenceQuery reference;

    OnOrderDeferred(OrderTransitions transitions, JdbcOrderRepository orders, ReferenceQuery reference) {
      super(transitions);
      this.orders = orders;
      this.reference = reference;
    }

    @Override
    public String consumerName() {
      return "ordering.on-order-deferred";
    }

    @Override
    public Class<OrderDeferred> eventType() {
      return OrderDeferred.class;
    }

    @Override
    public void on(EventEnvelope<OrderDeferred> envelope) {
      OrderDeferred deferred = envelope.payload();
      LocalDate next = reference.nextOperatingDay(deferred.serviceDate().plusDays(1));
      Order current = orders.find(deferred.orderId()).orElse(null);
      if (current != null
          && current.status() == OrderStatus.DEFERRED
          && !current.deliveryDate().isBefore(next)) {
        return;
      }
      transitions.apply(
          deferred.orderId(),
          OrderStatus.DEFERRED,
          o -> o.deferTo(next, deferred.skipCount()),
          "deferred by plan " + deferred.planId() + " (" + deferred.ruleId() + "): " + deferred.reason(),
          envelope);
    }
  }

  @Component
  static class OnOrderUnservable extends OrderingConsumer<OrderUnservable> {
    OnOrderUnservable(OrderTransitions transitions) {
      super(transitions);
    }

    @Override
    public String consumerName() {
      return "ordering.on-order-unservable";
    }

    @Override
    public Class<OrderUnservable> eventType() {
      return OrderUnservable.class;
    }

    @Override
    public void on(EventEnvelope<OrderUnservable> envelope) {
      OrderUnservable e = envelope.payload();
      transitions.move(
          e.orderId(), OrderStatus.UNSERVABLE, "unservable (" + e.ruleId() + "): " + e.reason(), envelope);
    }
  }

  /** Loading started on a trip: its orders can no longer be amended (ORD-06). */
  @Component
  static class OnLoadingStarted extends OrderingConsumer<LoadingStarted> {
    private final JdbcOrderRepository orders;

    OnLoadingStarted(OrderTransitions transitions, JdbcOrderRepository orders) {
      super(transitions);
      this.orders = orders;
    }

    @Override
    public String consumerName() {
      return "ordering.on-loading-started";
    }

    @Override
    public Class<LoadingStarted> eventType() {
      return LoadingStarted.class;
    }

    @Override
    public void on(EventEnvelope<LoadingStarted> envelope) {
      LoadingStarted e = envelope.payload();
      for (Order order : orders.ofTrip(e.tripId())) {
        transitions.move(order.orderId(), OrderStatus.LOADING, "loading started on " + e.vehicleId(), envelope);
      }
    }
  }

  @Component
  static class OnTripReleased extends OrderingConsumer<TripReleased> {
    OnTripReleased(OrderTransitions transitions) {
      super(transitions);
    }

    @Override
    public String consumerName() {
      return "ordering.on-trip-released";
    }

    @Override
    public Class<TripReleased> eventType() {
      return TripReleased.class;
    }

    @Override
    public void on(EventEnvelope<TripReleased> envelope) {
      TripReleased e = envelope.payload();
      for (ReleasedStop stop : e.stops()) {
        transitions.move(
            stop.orderId(), OrderStatus.IN_TRANSIT, "released on " + e.vehicleId(), envelope);
      }
    }
  }

  @Component
  static class OnDeliveryCompleted extends OrderingConsumer<DeliveryCompleted> {
    OnDeliveryCompleted(OrderTransitions transitions) {
      super(transitions);
    }

    @Override
    public String consumerName() {
      return "ordering.on-delivery-completed";
    }

    @Override
    public Class<DeliveryCompleted> eventType() {
      return DeliveryCompleted.class;
    }

    @Override
    public void on(EventEnvelope<DeliveryCompleted> envelope) {
      DeliveryCompleted e = envelope.payload();
      OrderStatus to =
          e.outcome() == DeliveryOutcome.PARTIAL
              ? OrderStatus.PARTIALLY_DELIVERED
              : OrderStatus.DELIVERED;
      transitions.move(e.orderId(), to, "delivery " + e.deliveryId() + " " + e.outcome(), envelope);
    }
  }

  @Component
  static class OnDeliveryFailed extends OrderingConsumer<DeliveryFailed> {
    OnDeliveryFailed(OrderTransitions transitions) {
      super(transitions);
    }

    @Override
    public String consumerName() {
      return "ordering.on-delivery-failed";
    }

    @Override
    public Class<DeliveryFailed> eventType() {
      return DeliveryFailed.class;
    }

    @Override
    public void on(EventEnvelope<DeliveryFailed> envelope) {
      DeliveryFailed e = envelope.payload();
      transitions.move(e.orderId(), OrderStatus.FAILED, "delivery failed: " + e.reason(), envelope);
    }
  }

  @Component
  static class OnReceiptConfirmed extends OrderingConsumer<ReceiptConfirmed> {
    OnReceiptConfirmed(OrderTransitions transitions) {
      super(transitions);
    }

    @Override
    public String consumerName() {
      return "ordering.on-receipt-confirmed";
    }

    @Override
    public Class<ReceiptConfirmed> eventType() {
      return ReceiptConfirmed.class;
    }

    @Override
    public void on(EventEnvelope<ReceiptConfirmed> envelope) {
      ReceiptConfirmed e = envelope.payload();
      transitions.move(
          e.orderId(), OrderStatus.RECEIVED, e.partial() ? "received, partial" : "received", envelope);
    }
  }

  /** The store did not confirm within the window (R-RCP-05). */
  @Component
  static class OnReceiptAutoClosed extends OrderingConsumer<ReceiptAutoClosed> {
    OnReceiptAutoClosed(OrderTransitions transitions) {
      super(transitions);
    }

    @Override
    public String consumerName() {
      return "ordering.on-receipt-auto-closed";
    }

    @Override
    public Class<ReceiptAutoClosed> eventType() {
      return ReceiptAutoClosed.class;
    }

    @Override
    public void on(EventEnvelope<ReceiptAutoClosed> envelope) {
      transitions.move(
          envelope.payload().orderId(), OrderStatus.UNCONFIRMED, "receipt auto-closed", envelope);
    }
  }

  /**
   * The store answered with a dispute. The goods are at the outlet and the
   * store has spoken, so the order is received; what is in dispute lives in an
   * Issues investigation, not in the order's status (issue #13, decision 1).
   *
   * <p>A dispute after auto-close finds the order already UNCONFIRMED, which is
   * terminal. That is expected, not illegal: it is counted and left alone, and
   * the late report is investigated in Issues (RCP-08).
   */
  @Component
  static class OnReceiptDisputed extends OrderingConsumer<ReceiptDisputed> {
    private final JdbcOrderRepository orders;
    private final Metrics metrics;

    OnReceiptDisputed(OrderTransitions transitions, JdbcOrderRepository orders, Metrics metrics) {
      super(transitions);
      this.orders = orders;
      this.metrics = metrics;
    }

    @Override
    public String consumerName() {
      return "ordering.on-receipt-disputed";
    }

    @Override
    public Class<ReceiptDisputed> eventType() {
      return ReceiptDisputed.class;
    }

    @Override
    public void on(EventEnvelope<ReceiptDisputed> envelope) {
      ReceiptDisputed e = envelope.payload();
      boolean closedAlready =
          orders.find(e.orderId()).map(o -> o.status() == OrderStatus.UNCONFIRMED).orElse(false);
      if (closedAlready) {
        metrics.increment("waypoint.order.late_dispute");
        return;
      }
      transitions.move(e.orderId(), OrderStatus.RECEIVED, "received, disputed", envelope);
    }
  }
}
