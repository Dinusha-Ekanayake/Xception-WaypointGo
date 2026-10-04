package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.config.RelayProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Delivers committed events from {@code integration.outbox_events} to every
 * {@link EventSubscriber} of their type, at least once.
 *
 * <p>The delivery half of the backbone (issue #6). One pass:
 *
 * <ol>
 *   <li><b>Claim.</b> Due rows are locked {@code FOR UPDATE SKIP LOCKED} and
 *       marked {@code processing} with a lease, so several replicas share the
 *       outbox without waiting on each other, and a replica that dies mid-batch
 *       loses its claim when the lease lapses (PLT-02).
 *   <li><b>Deliver.</b> Each subscriber runs in a transaction of its own, opened
 *       as its module role for the system actor, with its inbox claim inside. A
 *       subscriber that already applied the event is skipped; one that fails
 *       rolls back alone.
 *   <li><b>Settle.</b> The event becomes {@code published}, or {@code failed}
 *       with a backoff, or {@code dead} once its attempts are spent (PLT-03).
 * </ol>
 *
 * <p><b>Order.</b> Only the oldest undelivered event of an aggregate is ever
 * claimable, so one aggregate's events arrive in the order they were written. A
 * failing event therefore holds back the later events of its own aggregate until
 * it is delivered or dead-lettered, and nothing else. There is no order between
 * aggregates.
 *
 * <p>Dispatch is in process. Moving to a broker replaces {@link #deliver}, not
 * the subscribers (SYSTEM-ARCHITECTURE section 11 #3).
 */
@Component
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int ERROR_LIMIT = 500;

  private final Database database;
  private final ConsumerInbox inbox;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Metrics metrics;
  private final RelayProperties settings;
  private final Map<String, List<EventSubscriber<?>>> subscribersByType;

  public OutboxRelay(
      Database database,
      ConsumerInbox inbox,
      ObjectMapper mapper,
      Clock clock,
      Metrics metrics,
      RelayProperties settings,
      List<EventSubscriber<?>> subscribers) {
    this.database = database;
    this.inbox = inbox;
    this.mapper = mapper;
    this.clock = clock.realTime();
    this.metrics = metrics;
    this.settings = settings;
    this.subscribersByType = byType(subscribers);
  }

  /** What is waiting, for the lag gauge and the dead-letter alert. */
  public record Backlog(long open, Optional<Instant> oldestOpen, long dead) {}

  /**
   * Claims one batch and delivers it.
   *
   * @return how many events were claimed; zero means nothing was due
   */
  public int deliverBatch() {
    List<Claimed> batch = claim();
    for (Claimed event : batch) {
      deliver(event);
    }
    return batch.size();
  }

  public Backlog backlog() {
    return bookkeeping(
        () -> {
          Map<String, Object> open =
              database.queryOne(
                  "SELECT count(*) AS n, min(occurred_at) AS oldest FROM integration.outbox_events"
                      + " WHERE status IN ('pending','failed','processing')");
          Map<String, Object> dead =
              database.queryOne(
                  "SELECT count(*) AS n FROM integration.outbox_events WHERE status = 'dead'");
          Timestamp oldest = (Timestamp) open.get("oldest");
          return new Backlog(
              ((Number) open.get("n")).longValue(),
              Optional.ofNullable(oldest).map(Timestamp::toInstant),
              ((Number) dead.get("n")).longValue());
        });
  }

  private List<Claimed> claim() {
    Instant now = clock.now();
    List<Map<String, Object>> rows =
        bookkeeping(
            () ->
                database.query(
                    """
                    WITH due AS (
                        SELECT e.event_id
                          FROM integration.outbox_events e
                         WHERE e.status IN ('pending','failed','processing')
                           AND e.next_attempt_at <= ?
                           AND NOT EXISTS (
                               SELECT 1 FROM integration.outbox_events older
                                WHERE older.aggregate_type = e.aggregate_type
                                  AND older.aggregate_id = e.aggregate_id
                                  AND older.status IN ('pending','failed','processing')
                                  AND older.seq < e.seq)
                         ORDER BY e.seq
                         LIMIT ?
                           FOR UPDATE OF e SKIP LOCKED)
                    UPDATE integration.outbox_events o
                       SET status = 'processing',
                           attempts = o.attempts + 1,
                           next_attempt_at = ?
                      FROM due
                     WHERE o.event_id = due.event_id
                    RETURNING o.event_id, o.seq, o.event_type, o.event_version, o.payload::text AS payload,
                              o.occurred_at, o.producer, o.correlation_id, o.actor_id, o.attempts
                    """,
                    Timestamp.from(now),
                    settings.batchSize(),
                    Timestamp.from(now.plus(settings.lease()))));
    List<Claimed> batch = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      batch.add(Claimed.of(row));
    }
    // RETURNING promises no order, and the batch is delivered in write order.
    batch.sort(Comparator.comparingLong(Claimed::seq));
    return batch;
  }

  private void deliver(Claimed event) {
    List<EventSubscriber<?>> subscribers = subscribersByType.getOrDefault(event.type(), List.of());
    String failure = null;
    String previousCorrelation = MDC.get("correlationId");
    try {
      // Whatever a subscriber publishes in turn carries the id of the request
      // that started the chain, so the chain can be followed end to end.
      if (event.correlationId() != null) {
        MDC.put("correlationId", event.correlationId());
      }
      for (EventSubscriber<?> subscriber : subscribers) {
        try {
          deliverTo(subscriber, event);
        } catch (RuntimeException e) {
          metrics.increment("waypoint.outbox.consumer_failed", "consumer", subscriber.consumerName());
          log.warn(
              "Consumer {} failed on {} {} (attempt {}): {}",
              subscriber.consumerName(), event.type(), event.eventId(), event.attempts(), e.toString());
          if (failure == null) {
            failure = subscriber.consumerName() + ": " + describe(e);
          }
        }
      }
    } finally {
      if (previousCorrelation == null) {
        MDC.remove("correlationId");
      } else {
        MDC.put("correlationId", previousCorrelation);
      }
    }
    settle(event, failure);
  }

  private <E extends DomainEvent> void deliverTo(EventSubscriber<E> subscriber, Claimed event) {
    E payload;
    try {
      payload = mapper.readValue(event.payload(), subscriber.eventType());
    } catch (JsonProcessingException | RuntimeException e) {
      // The message names the field, never the value: a payload may hold personal data.
      throw new IllegalStateException(
          "Payload of " + event.type() + " v" + event.version() + " does not read as "
              + subscriber.eventType().getSimpleName() + " (" + e.getClass().getSimpleName() + ")");
    }
    EventEnvelope<E> envelope =
        new EventEnvelope<>(
            event.eventId(),
            event.type(),
            event.version(),
            event.occurredAt(),
            event.producer(),
            Optional.ofNullable(event.correlationId()),
            Optional.ofNullable(event.actorId()),
            payload);
    boolean applied =
        database.asSystem(
            subscriber.moduleRole(),
            () -> {
              if (!inbox.claim(subscriber.consumerName(), event.eventId())) {
                return false;
              }
              subscriber.on(envelope);
              return true;
            });
    metrics.increment(
        "waypoint.outbox.consumed",
        "consumer", subscriber.consumerName(),
        "outcome", applied ? "applied" : "duplicate");
  }

  private void settle(Claimed event, String failure) {
    Instant now = clock.now();
    // attempts in the guard: if the lease lapsed and another relay claimed the
    // event since, the outcome is that relay's to record, not ours.
    if (failure == null) {
      bookkeeping(
          () ->
              database.update(
                  "UPDATE integration.outbox_events SET status = 'published', last_error = NULL"
                      + " WHERE event_id = ? AND status = 'processing' AND attempts = ?",
                  event.eventId(),
                  event.attempts()));
      metrics.increment("waypoint.outbox.delivered", "type", event.type());
      metrics.record(
          "waypoint.outbox.delivery_delay",
          Math.max(0, Duration.between(event.occurredAt(), now).toMillis()));
      return;
    }
    if (event.attempts() >= settings.maxAttempts()) {
      bookkeeping(
          () ->
              database.update(
                  "UPDATE integration.outbox_events"
                      + " SET status = 'dead', last_error = ?, dead_lettered_at = ?"
                      + " WHERE event_id = ? AND status = 'processing' AND attempts = ?",
                  failure,
                  Timestamp.from(now),
                  event.eventId(),
                  event.attempts()));
      metrics.increment("waypoint.outbox.dead_lettered", "type", event.type());
      log.error(
          "Event {} {} dead-lettered after {} attempts: {}",
          event.type(), event.eventId(), event.attempts(), failure);
      return;
    }
    Duration wait =
        RelayBackoff.after(
            event.attempts(),
            settings.baseBackoff(),
            settings.maxBackoff(),
            ThreadLocalRandom.current().nextDouble());
    bookkeeping(
        () ->
            database.update(
                "UPDATE integration.outbox_events"
                    + " SET status = 'failed', last_error = ?, next_attempt_at = ?"
                    + " WHERE event_id = ? AND status = 'processing' AND attempts = ?",
                failure,
                Timestamp.from(now.plus(wait)),
                event.eventId(),
                event.attempts()));
    metrics.increment("waypoint.outbox.retried", "type", event.type());
  }

  /**
   * The relay's own reads and writes of the outbox, read committed. Serializable
   * here would set the relay's scans against every command that publishes, and
   * cost commands retries for bookkeeping that row locks already make safe.
   */
  private <T> T bookkeeping(java.util.function.Supplier<T> work) {
    return database.asSystemSeparately(ModuleRole.INTEGRATION, work);
  }

  private static String describe(RuntimeException e) {
    String message = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    return message.length() <= ERROR_LIMIT ? message : message.substring(0, ERROR_LIMIT);
  }

  /**
   * Routes by the {@code TYPE} constant every event record declares, so a
   * subscriber is matched without constructing an event. A subscriber whose
   * event has none, or a consumer name used twice, stops the process starting:
   * either would otherwise lose events silently.
   */
  private static Map<String, List<EventSubscriber<?>>> byType(List<EventSubscriber<?>> subscribers) {
    Map<String, List<EventSubscriber<?>>> byType = new HashMap<>();
    Set<String> names = new HashSet<>();
    for (EventSubscriber<?> subscriber : subscribers) {
      if (!names.add(subscriber.consumerName())) {
        throw new IllegalStateException("Two event consumers are named " + subscriber.consumerName());
      }
      byType.computeIfAbsent(typeOf(subscriber), t -> new ArrayList<>()).add(subscriber);
    }
    return byType;
  }

  private static String typeOf(EventSubscriber<?> subscriber) {
    try {
      return (String) subscriber.eventType().getField("TYPE").get(null);
    } catch (ReflectiveOperationException | RuntimeException e) {
      throw new IllegalStateException(
          subscriber.eventType().getName() + " declares no public static String TYPE, so "
              + subscriber.consumerName() + " could never be delivered to",
          e);
    }
  }

  private record Claimed(
      UUID eventId,
      long seq,
      String type,
      int version,
      String payload,
      Instant occurredAt,
      String producer,
      String correlationId,
      UUID actorId,
      int attempts) {

    static Claimed of(Map<String, Object> row) {
      return new Claimed(
          (UUID) row.get("event_id"),
          ((Number) row.get("seq")).longValue(),
          (String) row.get("event_type"),
          ((Number) row.get("event_version")).intValue(),
          (String) row.get("payload"),
          ((Timestamp) row.get("occurred_at")).toInstant(),
          (String) row.get("producer"),
          (String) row.get("correlation_id"),
          (UUID) row.get("actor_id"),
          ((Number) row.get("attempts")).intValue());
    }
  }
}
