package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderEvents.OrderAutoDeferred;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrdersClosed;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Cutoff;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * At the 16:00 cutoff, an order the warehouse has still not reserved moves to
 * the next run with reason {@code stock_unresolved} (R-STK-06, STK-03), and then
 * each depot's day is closed so its plan is drafted automatically (R-ORD-15).
 *
 * <p>Stock is never assumed (R-STK-05), so the order does not become demand:
 * it is deferred without a reservation, and only the warehouse's answer makes
 * it plannable. Each order is its own transaction, so one that fails does not
 * hold back the rest; a failure is counted and retried at the next run, which
 * also catches up any day a missed run left behind.
 */
@Component
public class CutoffJob implements ScheduledJob {
  private final Database database;
  private final JdbcOrderRepository orders;
  private final ReferenceQuery reference;
  private final EventPublisher events;
  private final Metrics metrics;
  /** Held here so the gauge reads a live value, not a boxed number that goes NaN after GC. */
  private final AtomicInteger deferredLastRun = new AtomicInteger();

  public CutoffJob(
      Database database,
      JdbcOrderRepository orders,
      ReferenceQuery reference,
      EventPublisher events,
      Metrics metrics) {
    this.database = database;
    this.orders = orders;
    this.reference = reference;
    this.events = events;
    this.metrics = metrics;
    metrics.gauge("waypoint.order.auto_deferred_last_run", deferredLastRun::get);
  }

  @Override
  public String name() {
    return "ordering.cutoff";
  }

  @Override
  public String cron() {
    return "0 0 16 * * *";
  }

  @Override
  public boolean onBusinessClock() {
    return true;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ORDERING;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many orders were deferred */
  int runAt(Instant now) {
    LocalDate closed = lastClosedServiceDate(now);
    List<UUID> due =
        database.asSystem(
            ModuleRole.ORDERING,
            () -> orders.unreservedDueBy(closed).stream().map(Order::orderId).toList());
    int deferred = 0;
    for (UUID orderId : due) {
      try {
        if (database.asSystem(ModuleRole.ORDERING, () -> defer(orderId, closed, now))) {
          deferred++;
        }
      } catch (DomainException | org.springframework.dao.DataAccessException e) {
        metrics.increment("waypoint.order.auto_defer_failed");
      }
    }
    deferredLastRun.set(deferred);
    closeDays(closed, now);
    return deferred;
  }

  /**
   * R-ORD-15: the cutoff closes each depot's day by itself, so the plan is drafted
   * without anyone pressing Close (Planning's {@code orders.closed} consumer
   * queues generation, R-PLN-41). A day the dispatcher already closed is left as
   * it is; a depot with no orders that day is closed with no event, since there
   * is nothing to plan. Each depot is its own transaction.
   *
   * @return how many depot-days were closed now
   */
  int closeDays(LocalDate serviceDate, Instant now) {
    int closedNow = 0;
    for (String depot : reference.depotCodes()) {
      try {
        if (database.asSystem(ModuleRole.ORDERING, () -> closeDay(depot, serviceDate, now))) {
          closedNow++;
        }
      } catch (DomainException | org.springframework.dao.DataAccessException e) {
        metrics.increment("waypoint.order.auto_close_failed");
      }
    }
    return closedNow;
  }

  private boolean closeDay(String depot, LocalDate serviceDate, Instant now) {
    if (orders.isClosed(depot, serviceDate)) {
      return false;
    }
    List<UUID> ids = orders.dueOn(depot, serviceDate).stream().map(Order::orderId).toList();
    // A null closer is the system, as in the status history (architecture rule 8).
    orders.close(depot, serviceDate, null, ids.size(), now);
    if (!ids.isEmpty()) {
      events.publish(Actor.SYSTEM, new OrdersClosed(depot, serviceDate, ids));
    }
    metrics.increment("waypoint.order.day_auto_closed");
    return true;
  }

  /** The latest service date whose ordering has closed at {@code now}. */
  static LocalDate lastClosedServiceDate(Instant now) {
    LocalDate tomorrow = now.atZone(Clock.OPERATING_ZONE).toLocalDate().plusDays(1);
    return Cutoff.hasPassed(tomorrow, now) ? tomorrow : tomorrow.minusDays(1);
  }

  private boolean defer(UUID orderId, LocalDate closed, Instant now) {
    Optional<Order> found = orders.find(orderId);
    if (found.isEmpty()
        || found.get().reservation().isPresent()
        || found.get().deliveryDate().isAfter(closed)) {
      return false;
    }
    Order current = found.get();
    LocalDate next = reference.nextOperatingDay(closed.plusDays(1));
    Order moved = current.deferTo(next, 0);
    orders.update(moved, current.rowVersion(), false, now);
    orders.recordStatus(
        orderId, Optional.of(current.status()), OrderStatus.DEFERRED, "stock_unresolved",
        null, Optional.empty(), now);
    events.publish(
        Actor.SYSTEM,
        new OrderAutoDeferred(
            orderId, current.outletId(), current.depotCode(), current.deliveryDate(), next,
            "stock_unresolved"));
    metrics.increment("waypoint.order.auto_deferred");
    return true;
  }
}
