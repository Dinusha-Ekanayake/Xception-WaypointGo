package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.issues.contract.IssueEvents.RedeliveryRequested;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderRef;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * An issue asked for the goods again: a new order linked to the original,
 * confirmed at once because it carries the original's reservation (A-11 in
 * ASSUMPTIONS.md). Planning sees it as ordinary demand through
 * {@code order.placed}; it does not consume {@code delivery.failed}.
 *
 * <p>One issue, one redelivery: {@code source_issue_id} is unique, so a
 * redelivered event finds the order it already made.
 */
@Component
public class OnRedeliveryRequested extends OrderingConsumer<RedeliveryRequested> {
  private static final Logger log = LoggerFactory.getLogger(OnRedeliveryRequested.class);

  private final JdbcOrderRepository orders;
  private final DeliveryDateResolver dates;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public OnRedeliveryRequested(
      OrderTransitions transitions,
      JdbcOrderRepository orders,
      DeliveryDateResolver dates,
      EventPublisher events,
      Metrics metrics,
      Clock clock) {
    super(transitions);
    this.orders = orders;
    this.dates = dates;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String consumerName() {
    return "ordering.on-redelivery-requested";
  }

  @Override
  public Class<RedeliveryRequested> eventType() {
    return RedeliveryRequested.class;
  }

  @Override
  public void on(EventEnvelope<RedeliveryRequested> envelope) {
    RedeliveryRequested e = envelope.payload();
    if (orders.findBySourceIssue(e.issueId()).isPresent()) {
      metrics.increment("waypoint.order.event_stale", "event", envelope.type());
      return;
    }
    Optional<Order> original = orders.find(e.originalOrderId());
    if (original.isEmpty()) {
      transitions.unknown(e.originalOrderId(), envelope);
      return;
    }
    Instant now = clock.now();
    DeliveryDate date = dates.resolve(original.get().depotCode(), e.requestedDate(), now);
    Order redelivery;
    try {
      redelivery =
          Order.redeliveryOf(
              original.get(),
              UuidV7.generate(now, random),
              OrderRef.derive(Actor.SYSTEM_ID, e.issueId()),
              e.requestedDate(),
              date.delivery());
    } catch (DomainException refused) {
      metrics.increment("waypoint.order.redelivery_refused");
      log.warn("Redelivery for issue {} refused: {}", e.issueId(), refused.getMessage());
      return;
    }
    orders.insert(redelivery, Actor.SYSTEM_ID, now, envelope.eventId(), Optional.of(e.issueId()));
    orders.recordStatus(
        redelivery.orderId(),
        Optional.empty(),
        OrderStatus.CONFIRMED,
        "redelivery of " + original.get().orderRef() + " for issue " + e.issueId() + ": " + e.reason(),
        envelope.actorId().orElse(null),
        Optional.of(envelope.eventId()),
        now);
    events.publish(Actor.SYSTEM, OrderMessages.placed(redelivery));
    metrics.increment("waypoint.order.redelivery_created");
  }
}
