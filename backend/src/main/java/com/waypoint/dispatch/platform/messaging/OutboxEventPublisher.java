package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes an event to {@code integration.outbox_events} in the caller's transaction.
 *
 * <p>This is the write half of the backbone only. Delivery is {@link OutboxRelay}'s
 * job: it claims pending rows, builds the {@code EventEnvelope} from the columns
 * written here and hands it to every subscriber of the type. Nothing here calls a
 * consumer, so an event can never escape a transaction that later rolls back.
 */
@Component
public class OutboxEventPublisher implements EventPublisher {
  private static final Pattern PRODUCER =
      Pattern.compile("^com\\.waypoint\\.dispatch\\.([a-z]+)\\.contract$");

  private final Database database;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Metrics metrics;
  private final RelaySignal relay;
  private final SecureRandom random = new SecureRandom();

  public OutboxEventPublisher(
      Database database, ObjectMapper mapper, Clock clock, Metrics metrics, RelaySignal relay) {
    this.database = database;
    this.mapper = mapper;
    this.clock = clock;
    this.metrics = metrics;
    this.relay = relay;
  }

  @Override
  public void publish(Actor actor, DomainEvent event) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Event " + event.type() + " published outside a transaction; it could announce a change"
              + " that never commits");
    }
    Instant now = clock.now();
    UUID eventId = UuidV7.generate(now, random);
    UUID actorId = actor == null || actor.isSystem() ? null : actor.userId();

    database.update(
        """
        INSERT INTO integration.outbox_events
            (event_id, aggregate_type, aggregate_id, event_type, event_version, payload,
             occurred_at, producer, correlation_id, actor_id)
        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        """,
        eventId,
        event.aggregateType(),
        event.aggregateId(),
        event.type(),
        event.version(),
        toJson(event),
        Timestamp.from(now),
        producerOf(event),
        MDC.get("correlationId"),
        actorId);
    // Only once the event is really there: a relay woken before the commit finds nothing.
    database.afterCommit(relay::signal);
    metrics.increment("waypoint.event.published", "type", event.type());
  }

  /**
   * The module that owns the event record. Every event lives in its producer's
   * {@code contract} package, which {@code EventCatalogueTest} enforces.
   */
  static String producerOf(DomainEvent event) {
    Matcher m = PRODUCER.matcher(event.getClass().getPackageName());
    if (!m.matches()) {
      throw new IllegalArgumentException(
          event.getClass().getName() + " is not in a module's contract package");
    }
    return m.group(1);
  }

  private String toJson(DomainEvent event) {
    try {
      return mapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Event " + event.type() + " is not serialisable", e);
    }
  }
}
