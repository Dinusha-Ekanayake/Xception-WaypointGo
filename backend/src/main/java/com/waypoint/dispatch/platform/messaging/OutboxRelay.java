package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.config.OutboxProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.web.CorrelationIdFilter;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Delivers what {@link OutboxEventPublisher} wrote: at least once, in order per
 * aggregate, and never to an event whose transaction rolled back (the row would
 * not exist).
 *
 * <p>One pass has three steps, in three transactions, so a subscriber's own
 * transaction is never nested inside the relay's:
 *
 * <ol>
 *   <li><b>Claim.</b> Due rows are taken {@code FOR UPDATE SKIP LOCKED} and marked
 *       {@code processing} with a lease, so a second instance takes a different
 *       batch instead of waiting on the first. A relay that dies mid batch leaves
 *       leases that expire, and the rows are claimed again (PLT-02).
 *   <li><b>Deliver.</b> Each subscriber of the type runs in a transaction of its
 *       own under its own module role, after recording {@code (consumer, event)}
 *       in {@link ConsumerInbox}. A redelivery therefore skips the subscribers that
 *       already applied it and runs only those that failed.
 *   <li><b>Settle.</b> The event becomes {@code published}, or {@code failed} with
 *       a retry time, or {@code dead} once attempts run out (PLT-03).
 * </ol>
 *
 * <p>Ordering: an event is not claimed while an earlier event of the same
 * aggregate is still pending, failed or processing. A dead event does not block,
 * so a poison event never stops the queue; whoever replays it accepts that it now
 * arrives after its successors.
 *
 * <p>A failure stores the exception class and, for a {@link DomainException}, its
 * message, never the payload or a driver message, either of which can carry
 * personal data.
 */
@Component
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final java.time.Duration GAUGE_REFRESH = java.time.Duration.ofSeconds(15);
  private static final int ERROR_LIMIT = 300;

  private final Database database;
  private final SubscriberRegistry registry;
  private final ConsumerInbox inbox;
  private final ObjectMapper mapper;
  private final Metrics metrics;
  private final Clock clock;
  private final OutboxProperties properties;
  private final RetryBackoff backoff;
  private final SecureRandom random = new SecureRandom();

  private final AtomicLong lagSeconds = new AtomicLong();
  private final AtomicLong deadCount = new AtomicLong();
  private volatile Instant nextGaugeRefresh = Instant.MIN;

  public OutboxRelay(
      Database database,
      SubscriberRegistry registry,
      ConsumerInbox inbox,
      ObjectMapper mapper,
      Metrics metrics,
      Clock clock,
      OutboxProperties properties) {
    this.database = database;
    this.registry = registry;
    this.inbox = inbox;
    this.mapper = mapper;
    this.metrics = metrics;
    this.clock = clock;
    this.properties = properties;
    this.backoff =
        new RetryBackoff(properties.maxAttempts(), properties.backoffBase(), properties.backoffCap());
    metrics.gauge("waypoint.outbox.lag_seconds", lagSeconds::get);
    metrics.gauge("waypoint.outbox.dead", deadCount::get);
  }

  /** One pass at the current time. @return events handled, so a caller can drain until zero */
  public int runOnce() {
    return runOnce(clock.now());
  }

  /** One pass as of {@code now}. Public so a test can step time past a backoff. */
  public int runOnce(Instant now) {
    List<Claimed> batch = claim(now);
    for (Claimed event : batch) {
      deliver(event, now);
    }
    if (batch.isEmpty() && !now.isBefore(nextGaugeRefresh)) {
      refreshGauges(now);
    }
    return batch.size();
  }

  private List<Claimed> claim(Instant now) {
    Timestamp at = Timestamp.from(now);
    Timestamp until = Timestamp.from(now.plus(properties.lease()));
    List<Map<String, Object>> rows =
        database.asSystem(
            ModuleRole.INTEGRATION,
            () ->
                database.query(
                    """
                    WITH due AS (
                      SELECT o.event_id
                        FROM integration.outbox_events o
                       WHERE ((o.status IN ('pending', 'failed') AND o.next_attempt_at <= ?)
                           OR (o.status = 'processing' AND o.locked_until <= ?))
                         AND NOT EXISTS (
                             SELECT 1
                               FROM integration.outbox_events e
                              WHERE e.aggregate_type = o.aggregate_type
                                AND e.aggregate_id = o.aggregate_id
                                AND e.status IN ('pending', 'failed', 'processing')
                                AND (e.occurred_at, e.event_id) < (o.occurred_at, o.event_id))
                       ORDER BY o.occurred_at, o.event_id
                       LIMIT ?
                         FOR UPDATE OF o SKIP LOCKED)
                    UPDATE integration.outbox_events t
                       SET status = 'processing', locked_until = ?
                      FROM due
                     WHERE t.event_id = due.event_id
                    RETURNING t.event_id, t.event_type, t.event_version, t.occurred_at,
                              t.producer, t.correlation_id, t.actor_id, t.attempts,
                              t.payload::text AS payload
                    """,
                    at,
                    at,
                    properties.batchSize(),
                    until));
    return rows.stream()
        .map(
            row ->
                new Claimed(
                    (UUID) row.get("event_id"),
                    (String) row.get("event_type"),
                    ((Number) row.get("event_version")).intValue(),
                    ((Timestamp) row.get("occurred_at")).toInstant(),
                    (String) row.get("producer"),
                    (String) row.get("correlation_id"),
                    (UUID) row.get("actor_id"),
                    ((Number) row.get("attempts")).intValue(),
                    (String) row.get("payload")))
        .sorted(Comparator.comparing(Claimed::occurredAt).thenComparing(Claimed::eventId))
        .toList();
  }

  private void deliver(Claimed event, Instant now) {
    List<String> failures = new java.util.ArrayList<>();
    String previousCorrelation = MDC.get(CorrelationIdFilter.MDC_KEY);
    // The chain of events started by one request stays traceable: whatever a
    // subscriber publishes or audits carries the id of the request that began it.
    if (event.correlationId() != null) {
      MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
    }
    try {
      for (EventSubscriber<? extends DomainEvent> subscriber : registry.forType(event.type())) {
        try {
          deliverTo(subscriber, event);
        } catch (RuntimeException e) {
          failures.add(subscriber.consumerName() + ": " + describe(e));
          log.warn(
              "Event {} ({}) failed for {}: {}",
              event.eventId(),
              event.type(),
              subscriber.consumerName(),
              describe(e));
          log.debug("Delivery failure detail", e);
        }
      }
    } finally {
      if (previousCorrelation == null) {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
      } else {
        MDC.put(CorrelationIdFilter.MDC_KEY, previousCorrelation);
      }
    }
    try {
      settle(event, failures, now);
    } catch (RuntimeException e) {
      // The lease expires and the event is claimed again; the inbox makes that
      // a no-op for every subscriber that already succeeded.
      log.warn("Could not settle event {}: {}", event.eventId(), describe(e));
      metrics.increment("waypoint.outbox.settle_failed");
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void deliverTo(EventSubscriber subscriber, Claimed event) {
    Object payload;
    try {
      payload = mapper.readValue(event.payload(), subscriber.eventType());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Payload does not match " + subscriber.eventType().getSimpleName(), e);
    }
    EventEnvelope envelope =
        new EventEnvelope(
            event.eventId(),
            event.type(),
            event.version(),
            event.occurredAt(),
            event.producer(),
            Optional.ofNullable(event.correlationId()),
            Optional.ofNullable(event.actorId()),
            (DomainEvent) payload);
    database.asSystem(
        subscriber.moduleRole(),
        () -> {
          if (inbox.claim(subscriber.consumerName(), event.eventId())) {
            subscriber.on(envelope);
          }
        });
  }

  private void settle(Claimed event, List<String> failures, Instant now) {
    Timestamp at = Timestamp.from(now);
    if (failures.isEmpty()) {
      int updated =
          database.asSystem(
              ModuleRole.INTEGRATION,
              () ->
                  database.update(
                      "UPDATE integration.outbox_events SET status = 'published', published_at = ?,"
                          + " locked_until = NULL, last_error = NULL"
                          + " WHERE event_id = ? AND status = 'processing'",
                      at,
                      event.eventId()));
      if (updated == 1) {
        metrics.increment("waypoint.outbox.delivered", "type", event.type());
      }
      return;
    }

    int failed = event.attempts() + 1;
    boolean dead = backoff.isExhausted(failed);
    String error = truncate(String.join("; ", failures));
    Timestamp retryAt = Timestamp.from(now.plus(backoff.delay(failed, random::nextDouble)));
    int updated =
        database.asSystem(
            ModuleRole.INTEGRATION,
            () -> {
              int rows =
                  database.update(
                      "UPDATE integration.outbox_events SET status = ?, attempts = ?,"
                          + " next_attempt_at = ?, last_error = ?, locked_until = NULL,"
                          + " dead_lettered_at = ?"
                          + " WHERE event_id = ? AND status = 'processing'",
                      dead ? "dead" : "failed",
                      failed,
                      retryAt,
                      error,
                      dead ? at : null,
                      event.eventId());
              if (rows == 1) {
                database.update(
                    "INSERT INTO integration.outbox_attempts"
                        + " (event_id, attempt, attempted_at, outcome, error) VALUES (?, ?, ?, ?, ?)",
                    event.eventId(),
                    failed,
                    at,
                    dead ? "dead" : "failed",
                    error);
              }
              return rows;
            });
    if (updated == 1) {
      metrics.increment(dead ? "waypoint.outbox.dead_lettered" : "waypoint.outbox.retried", "type", event.type());
    }
  }

  /** Reads the two numbers an operator alerts on. Unthrottled, for tests and the idle pass. */
  void refreshGauges(Instant now) {
    nextGaugeRefresh = now.plus(GAUGE_REFRESH);
    try {
      Map<String, Object> oldest =
          database.asSystem(
              ModuleRole.INTEGRATION,
              () ->
                  database.queryOne(
                      "SELECT min(occurred_at) AS oldest FROM integration.outbox_events"
                          + " WHERE status IN ('pending', 'failed', 'processing')"));
      Map<String, Object> dead =
          database.asSystem(
              ModuleRole.INTEGRATION,
              () ->
                  database.queryOne(
                      "SELECT count(*) AS dead FROM integration.outbox_events WHERE status = 'dead'"));
      Timestamp oldestAt = (Timestamp) oldest.get("oldest");
      lagSeconds.set(
          oldestAt == null ? 0 : Math.max(0, now.getEpochSecond() - oldestAt.toInstant().getEpochSecond()));
      deadCount.set(((Number) dead.get("dead")).longValue());
    } catch (RuntimeException e) {
      // A stale gauge is better than none, and the failed pass is visible in its own counter.
      metrics.increment("waypoint.outbox.gauge_failed");
    }
  }

  static String describe(Throwable failure) {
    StringBuilder text = new StringBuilder(failure.getClass().getSimpleName());
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof DomainException domain) {
        text.append(": ").append(domain.getMessage());
        break;
      }
      if (t instanceof SQLException sql && sql.getSQLState() != null) {
        text.append(" (SQLSTATE ").append(sql.getSQLState()).append(')');
        break;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return text.toString();
  }

  private static String truncate(String text) {
    return text.length() <= ERROR_LIMIT ? text : text.substring(0, ERROR_LIMIT) + "...";
  }

  private record Claimed(
      UUID eventId,
      String type,
      int version,
      Instant occurredAt,
      String producer,
      String correlationId,
      UUID actorId,
      int attempts,
      String payload) {}
}
