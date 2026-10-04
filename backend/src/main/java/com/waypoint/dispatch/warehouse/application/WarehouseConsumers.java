package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderCancelled;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.domain.Placement;
import com.waypoint.dispatch.warehouse.domain.Placement.State;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcPlacementRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcStatusRequestRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Keeps the warehouse's order in step with Waypoint's, from Waypoint's events
 * (R-STK-13). Each consumer only queues the call: the relay's transaction is not
 * the place to wait on an external system, and {@link StatusRequestJob} retries
 * the call until the warehouse acknowledges it.
 */
public final class WarehouseConsumers {
  private WarehouseConsumers() {}

  abstract static class WarehouseConsumer<E extends DomainEvent> implements EventSubscriber<E> {
    final JdbcPlacementRepository placements;
    final JdbcStatusRequestRepository requests;
    final Metrics metrics;
    final Clock clock;
    private final SecureRandom random = new SecureRandom();

    WarehouseConsumer(
        JdbcPlacementRepository placements, JdbcStatusRequestRepository requests, Metrics metrics,
        Clock clock) {
      this.placements = placements;
      this.requests = requests;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.WAREHOUSE;
    }

    void request(UUID orderId, String warehouseOrderRef, String target, String cause) {
      Instant now = clock.now();
      if (requests.request(UuidV7.generate(now, random), orderId, warehouseOrderRef, target, cause, now)) {
        metrics.increment("waypoint.warehouse.status_requested", "target", target);
      }
    }

    /** The warehouse order Waypoint holds for an order, if any. */
    Optional<String> held(UUID orderId) {
      return placements.findForOrder(orderId)
          .filter(p -> p.state() == State.PLACED || p.state() == State.PARTIAL)
          .flatMap(Placement::warehouseOrderRef);
    }
  }

  /** The only path that releases a reservation (D-H): deferral never cancels. */
  @Component
  public static class OnOrderCancelled extends WarehouseConsumer<OrderCancelled> {

    public OnOrderCancelled(
        JdbcPlacementRepository placements, JdbcStatusRequestRepository requests, Metrics metrics,
        Clock clock) {
      super(placements, requests, metrics, clock);
    }

    @Override
    public String consumerName() {
      return "warehouse.on-order-cancelled";
    }

    @Override
    public Class<OrderCancelled> eventType() {
      return OrderCancelled.class;
    }

    @Override
    public void on(EventEnvelope<OrderCancelled> envelope) {
      OrderCancelled e = envelope.payload();
      Optional<String> ref = e.warehouseOrderRef().or(() -> held(e.orderId()));
      ref.ifPresent(r -> request(e.orderId(), r, "cancelled", "order.cancelled: " + e.reason()));
      // A stock-unknown order may still have a lost attempt at the warehouse:
      // from now on the retry job only looks for it to cancel it.
      Instant now = clock.now();
      for (Placement p : placements.forOrder(e.orderId())) {
        if (p.awaitingWarehouse() && !p.compensateOnly()) {
          placements.update(
              new Placement(p.placementId(), p.orderRef(), p.orderId(), p.depotCode(), p.warehouseCode(),
                  p.lines(), p.attempts(), p.state(), true, p.warehouseOrderRef(), p.warehouseStatus(),
                  p.expiresAt(), p.result(), p.retryCount(), p.nextAttemptAt(), p.createdAt(),
                  p.rowVersion()),
              now);
        }
      }
    }
  }

  @Component
  public static class OnTripReleased extends WarehouseConsumer<TripReleased> {

    public OnTripReleased(
        JdbcPlacementRepository placements, JdbcStatusRequestRepository requests, Metrics metrics,
        Clock clock) {
      super(placements, requests, metrics, clock);
    }

    @Override
    public String consumerName() {
      return "warehouse.on-trip-released";
    }

    @Override
    public Class<TripReleased> eventType() {
      return TripReleased.class;
    }

    @Override
    public void on(EventEnvelope<TripReleased> envelope) {
      for (ReleasedStop stop : envelope.payload().stops()) {
        Optional<String> ref = held(stop.orderId());
        if (ref.isEmpty()) {
          metrics.increment("waypoint.warehouse.status_unrequestable", "target", "shipped");
          continue;
        }
        request(stop.orderId(), ref.get(), "shipped", "trip.released " + envelope.payload().tripId());
      }
    }
  }

  @Component
  public static class OnDeliveryCompleted extends WarehouseConsumer<DeliveryCompleted> {

    public OnDeliveryCompleted(
        JdbcPlacementRepository placements, JdbcStatusRequestRepository requests, Metrics metrics,
        Clock clock) {
      super(placements, requests, metrics, clock);
    }

    @Override
    public String consumerName() {
      return "warehouse.on-delivery-completed";
    }

    @Override
    public Class<DeliveryCompleted> eventType() {
      return DeliveryCompleted.class;
    }

    @Override
    public void on(EventEnvelope<DeliveryCompleted> envelope) {
      DeliveryCompleted e = envelope.payload();
      // Goods that never reached the outlet stay shipped; Issues decides what next.
      if (e.outcome() != DeliveryOutcome.DELIVERED && e.outcome() != DeliveryOutcome.PARTIAL) {
        return;
      }
      Optional<String> ref = held(e.orderId());
      if (ref.isEmpty()) {
        metrics.increment("waypoint.warehouse.status_unrequestable", "target", "delivered");
        return;
      }
      request(e.orderId(), ref.get(), "delivered", "delivery.completed " + e.deliveryId());
    }
  }
}
