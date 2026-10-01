package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderCommands;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrdersClosed;
import com.waypoint.dispatch.ordering.domain.Cutoff;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The dispatcher declaring a depot-day's demand final, so Planning may generate.
 *
 * <p>Refused before the 16:00 cutoff on the server clock (R-ORD-01, R-ORD-07):
 * closing early would strand orders stores are still entitled to place. Once
 * closed, placement for that day rolls forward with reason {@code closed}.
 * Closing an already-closed day is answered with the closure, not a second event.
 */
@Component
public class CloseOrdersForDayHandler implements CommandHandler {
  private final Database database;
  private final JdbcOrderRepository orders;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;

  public CloseOrdersForDayHandler(
      Database database,
      JdbcOrderRepository orders,
      EventPublisher events,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.orders = orders;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return OrderCommands.CLOSE_FOR_DAY;
  }

  @Override
  public String action() {
    return OrderCommands.CLOSE_FOR_DAY;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ORDERING;
  }

  @Override
  public String resource(Command command) {
    String depot = CommandPayload.of(command).text("depotCode");
    return depot == null ? null : "wpt:order:depot:" + depot;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    String depot = payload.requiredText("depotCode");
    LocalDate serviceDate = payload.date("serviceDate");
    Instant now = clock.now();

    Map<String, Object> scope =
        database.queryOne("SELECT app.actor_has_depot(?) AS ok", depot);
    if (!Boolean.TRUE.equals(scope.get("ok"))) {
      throw new DomainException(ErrorCode.FORBIDDEN, "Depot " + depot + " is outside the actor's scope");
    }
    if (!Cutoff.hasPassed(serviceDate, now)) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "Ordering for " + serviceDate + " closes at " + Cutoff.closesAt(serviceDate)
              + "; it cannot be closed early",
          List.of("R-ORD-01", "R-ORD-07"));
    }

    List<UUID> ids = orders.dueOn(depot, serviceDate).stream().map(Order::orderId).toList();
    if (orders.isClosed(depot, serviceDate)) {
      return body(depot, serviceDate, ids, true);
    }
    orders.close(depot, serviceDate, actor.userId(), ids.size(), now);
    events.publish(actor, new OrdersClosed(depot, serviceDate, ids));
    metrics.increment("waypoint.order.day_closed");
    return body(depot, serviceDate, ids, false);
  }

  private static Map<String, Object> body(
      String depot, LocalDate date, List<UUID> ids, boolean alreadyClosed) {
    return Map.of(
        "depotCode", depot,
        "serviceDate", date.toString(),
        "orderCount", ids.size(),
        "alreadyClosed", alreadyClosed);
  }
}
