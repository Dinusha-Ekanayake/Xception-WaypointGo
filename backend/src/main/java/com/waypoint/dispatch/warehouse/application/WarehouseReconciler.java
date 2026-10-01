package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseDiscrepancyFound;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseOrderStatusChanged;
import com.waypoint.dispatch.warehouse.domain.CircuitBreaker;
import com.waypoint.dispatch.warehouse.domain.Placement;
import com.waypoint.dispatch.warehouse.domain.Placement.State;
import com.waypoint.dispatch.warehouse.domain.WarehouseLifecycle;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Answered;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcDiscrepancyRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcPlacementRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcStatusRequestRepository;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Compares the warehouse's view of each held order with Waypoint's, since the
 * warehouse has no webhook yet (polling fallback, MODULES "Inbound").
 *
 * <p>Raise only, never correct (open decision 4, recommendation taken): a
 * mismatch becomes {@code warehouse.discrepancy_found} for Issues. The one
 * exception is an orphan, a warehouse order whose Waypoint order never committed
 * (STK-08): nothing in Waypoint will ever ship or cancel it, so it is released.
 */
@Component
public class WarehouseReconciler {
  /** Long enough that an order still being committed is not taken for an orphan. */
  private static final Duration ORPHAN_AFTER = Duration.ofMinutes(10);

  private final Database database;
  private final JdbcPlacementRepository placements;
  private final JdbcStatusRequestRepository requests;
  private final JdbcDiscrepancyRepository discrepancies;
  private final WarehouseHttpClient client;
  private final OrderQuery orders;
  private final EventPublisher events;
  private final Metrics metrics;
  private final SecureRandom random = new SecureRandom();

  public WarehouseReconciler(
      Database database,
      JdbcPlacementRepository placements,
      JdbcStatusRequestRepository requests,
      JdbcDiscrepancyRepository discrepancies,
      WarehouseHttpClient client,
      OrderQuery orders,
      EventPublisher events,
      Metrics metrics) {
    this.database = database;
    this.placements = placements;
    this.requests = requests;
    this.discrepancies = discrepancies;
    this.client = client;
    this.orders = orders;
    this.events = events;
    this.metrics = metrics;
  }

  /** Reads the warehouse status of every open placement and records changes. */
  public int poll(Instant now, int limit) {
    if (!client.configured()) {
      return 0;
    }
    int changed = 0;
    for (Placement p : database.asSystem(ModuleRole.WAREHOUSE, () -> placements.open(limit))) {
      if (client.circuitState() == CircuitBreaker.State.OPEN) {
        break;
      }
      WarehouseReply reply = client.order(p.warehouseOrderRef().get());
      if (reply instanceof Answered answered
          && !Optional.of(answered.order().status()).equals(p.warehouseStatus())) {
        observed(p, answered.order().status(), now);
        changed++;
      }
    }
    return changed;
  }

  /**
   * One warehouse status, from polling or the webhook. Expiry and cancellation
   * reach Ordering; a cancellation Waypoint never asked for is also raised
   * (STK-11). Shipped and delivered were Waypoint's own requests.
   */
  public void observed(Placement p, String status, Instant now) {
    if (!WarehouseLifecycle.isKnown(status)) {
      metrics.increment("waypoint.warehouse.unknown_status");
      return;
    }
    database.asSystem(ModuleRole.WAREHOUSE, () -> {
      Placement current = placements.findByRef(p.orderRef()).orElse(p);
      boolean released = "cancelled".equals(status) || "expired".equals(status);
      placements.update(
          PlacementSender.copy(current, released ? State.RELEASED : current.state(), current.attempts(),
              current.warehouseOrderRef(), Optional.of(status), released ? Optional.empty() : current.expiresAt(),
              current.result(), current.retryCount(), current.nextAttemptAt()),
          now);
      if (!released || current.orderRef().contains("#amend-")) {
        return;
      }
      events.publish(Actor.SYSTEM, new WarehouseOrderStatusChanged(
          current.orderId(), current.warehouseOrderRef(), status, Optional.empty()));
      String ref = current.warehouseOrderRef().orElse("");
      if ("cancelled".equals(status) && !requests.wasRequested(ref, "cancelled")) {
        metrics.increment("waypoint.warehouse.cancelled_outside");
        raise(current, "cancelled_outside", null, status,
            "warehouse order " + ref + " was cancelled outside Waypoint", now);
      }
    });
  }

  /**
   * Compares held placements with their Waypoint orders, each step in a
   * transaction of its own. For the scheduled job.
   *
   * @param serviceDate only orders delivered that day, or every open one when empty
   * @return how many discrepancies were newly raised
   */
  public int reconcile(Instant now, Optional<LocalDate> serviceDate, int limit) {
    return reconcile(now, serviceDate, limit, true, new Tx() {
      @Override
      public <T> T run(Supplier<T> work) {
        return database.asSystem(ModuleRole.WAREHOUSE, work);
      }
    });
  }

  /**
   * The same, inside the caller's transaction: the {@code warehouse:Reconcile}
   * command. Raise only, orphans included: the caller's order reads are scoped to
   * the caller, so an order outside their scope would look like an orphan.
   */
  public int reconcileWithin(Instant now, Optional<LocalDate> serviceDate, int limit) {
    return reconcile(now, serviceDate, limit, false, new Tx() {
      @Override
      public <T> T run(Supplier<T> work) {
        return work.get();
      }
    });
  }

  private interface Tx {
    <T> T run(Supplier<T> work);
  }

  private int reconcile(
      Instant now, Optional<LocalDate> serviceDate, int limit, boolean releaseOrphans, Tx tx) {
    int raised = 0;
    for (Placement p : tx.run(() -> placements.open(limit))) {
      if (p.orderRef().contains("#amend-") || p.warehouseStatus().isEmpty()) {
        continue;
      }
      Optional<OrderView> order = tx.run(() -> orders.order(p.orderId()));
      if (order.isEmpty()) {
        if (releaseOrphans && p.createdAt().plus(ORPHAN_AFTER).isBefore(now)) {
          raised += tx.run(() -> orphan(p, now)) ? 1 : 0;
        }
        continue;
      }
      if (serviceDate.isPresent() && !serviceDate.get().equals(order.get().deliveryDate())) {
        continue;
      }
      String waypoint = order.get().status().name();
      Optional<Set<String>> expected = WarehouseLifecycle.expectedFor(waypoint);
      String warehouse = p.warehouseStatus().get();
      if (expected.isPresent() && !expected.get().contains(warehouse)) {
        boolean added = tx.run(() -> raise(p, "status_mismatch", waypoint, warehouse,
            "Waypoint order is " + waypoint + " but warehouse order " + p.warehouseOrderRef().get()
                + " is " + warehouse, now));
        raised += added ? 1 : 0;
      }
    }
    metrics.increment("waypoint.warehouse.reconcile_run");
    return raised;
  }

  /** STK-08: a warehouse order no Waypoint order owns. Released, and raised so a person sees it. */
  private boolean orphan(Placement p, Instant now) {
    String ref = p.warehouseOrderRef().get();
    metrics.increment("waypoint.warehouse.orphan_released");
    requests.request(UuidV7.generate(now, random), p.orderId(), ref, "cancelled", "orphan", now);
    placements.update(
        PlacementSender.copy(p, State.RELEASED, p.attempts(), p.warehouseOrderRef(), p.warehouseStatus(),
            Optional.empty(), p.result(), p.retryCount(), Optional.empty()),
        now);
    return raise(p, "orphan_released", null, p.warehouseStatus().orElse(null),
        "warehouse order " + ref + " has no Waypoint order; cancelled", now);
  }

  private boolean raise(
      Placement p, String kind, String waypointStatus, String warehouseStatus, String detail, Instant now) {
    boolean added = discrepancies.raise(
        UuidV7.generate(now, random), p.orderId(), p.warehouseOrderRef().orElse(p.orderRef()), kind,
        waypointStatus, warehouseStatus, detail, now);
    if (added) {
      metrics.increment("waypoint.warehouse.discrepancy", "kind", kind);
      events.publish(Actor.SYSTEM, new WarehouseDiscrepancyFound(
          p.orderId(), p.depotCode(), String.valueOf(waypointStatus), String.valueOf(warehouseStatus), detail));
    }
    return added;
  }
}
