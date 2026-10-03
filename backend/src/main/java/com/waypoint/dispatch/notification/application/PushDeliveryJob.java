package com.waypoint.dispatch.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.notification.domain.Delivery;
import com.waypoint.dispatch.notification.domain.Delivery.Outcome;
import com.waypoint.dispatch.notification.domain.Delivery.RetryPolicy;
import com.waypoint.dispatch.notification.domain.Delivery.Status;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository.DuePush;
import com.waypoint.dispatch.platform.config.PushProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.Clock;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Sends pending pushes, outside any transaction (R-NOT-05).
 *
 * <p>Each run claims a batch in one short transaction, leasing the rows so no
 * other instance takes them, then sends each with no transaction open, and
 * records each outcome in a transaction of its own. A failure waits longer each
 * time and is dead after the configured attempts (PLT-03); a subscription the
 * push service says is gone is expired and never tried again (NOT-01). A dead
 * push stays with its last error: proof of what did not reach the device.
 */
@Component
public class PushDeliveryJob implements ScheduledJob {
  /** Events that need a decision now are pushed as urgent, so a sleeping phone wakes. */
  static final Set<String> URGENT =
      Set.of("loading.shortfall", "delivery.failed", "issue.escalated", "vehicle.fault_reported", "order.unservable");

  private final Database database;
  private final JdbcNotificationRepository repository;
  private final PushGateway push;
  private final PushProperties properties;
  private final ObjectMapper json;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final AtomicLong dead = new AtomicLong();

  PushDeliveryJob(
      Database database,
      JdbcNotificationRepository repository,
      PushGateway push,
      PushProperties properties,
      ObjectMapper json,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.repository = repository;
    this.push = push;
    this.properties = properties;
    this.json = json;
    this.metrics = metrics;
    this.clock = clock;
    metrics.gauge("waypoint.notification.dead", dead::get);
    metrics.gauge("waypoint.notification.push_enabled", () -> push.config().enabled() ? 1 : 0);
  }

  @Override
  public String name() {
    return "notification.push";
  }

  @Override
  public String cron() {
    return "*/15 * * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.NOTIFICATION;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many pushes were attempted */
  int runAt(Instant now) {
    if (!push.config().enabled()) {
      refreshDead();
      return 0;
    }
    RetryPolicy policy = new RetryPolicy(properties.maxAttempts(), properties.baseBackoff(), properties.maxBackoff());
    // Long enough for every send in the batch to time out once, so a claimed
    // push is not claimed again by another instance while it is still in flight.
    Instant leaseUntil = now.plus(properties.timeout().multipliedBy(properties.batchSize() + 1L));
    List<DuePush> due =
        database.asSystem(ModuleRole.NOTIFICATION, () -> repository.claimDue(now, leaseUntil, properties.batchSize()));
    for (DuePush d : due) {
      attempt(d, policy);
    }
    refreshDead();
    return due.size();
  }

  private void attempt(DuePush d, RetryPolicy policy) {
    Outcome outcome;
    Optional<String> error;
    Instant done;
    if (!"active".equals(d.subscriptionStatus())) {
      // Unsubscribed or expired after this was queued: nothing to send to.
      done = clock.now();
      outcome = new Outcome(Status.DEAD, d.attempts(), Optional.empty(), false);
      error = Optional.of("subscription " + d.subscriptionStatus());
    } else {
      PushGateway.Response response =
          push.send(
              new PushGateway.Target(d.endpoint(), d.p256dhKey(), d.authSecret()),
              payload(d),
              properties.ttl(),
              URGENT.contains(d.eventType()));
      done = clock.now();
      outcome = Delivery.after(response.result(), d.attempts(), policy, done, random.nextDouble());
      error = outcome.status() == Status.SENT ? Optional.empty() : Optional.of(response.detail());
    }
    try {
      Outcome recorded = outcome;
      Optional<String> detail = error;
      Instant at = done;
      database.asSystem(
          ModuleRole.NOTIFICATION,
          () -> {
            repository.recordAttempt(d.deliveryId(), d.rowVersion(), recorded, detail, at);
            if (recorded.expireSubscription()) {
              repository.endSubscription(d.subscriptionId(), "expired", at);
            }
          });
    } catch (DomainException | DataAccessException e) {
      // Another instance took the row after the lease ran out; its outcome stands.
      metrics.increment("waypoint.notification.push_record_failed");
      return;
    }
    metrics.increment("waypoint.notification.delivery", "channel", "push", "status", outcome.status().code());
    if (outcome.expireSubscription()) {
      metrics.increment("waypoint.notification.subscription_expired");
    }
    if (outcome.status() == Status.SENT) {
      metrics.record(
          "waypoint.notification.push_latency",
          Math.max(0, Duration.between(d.notificationCreatedAt(), done).toMillis()));
    }
  }

  /** What the service worker receives: enough to show it and to open its subject. */
  private byte[] payload(DuePush d) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("notificationId", d.notificationId());
    body.put("eventType", d.eventType());
    // A push message is one record of at most 4 KB once encrypted; the inbox keeps the full text.
    body.put("title", clip(d.title(), 200));
    body.put("body", clip(d.body(), 1000));
    body.put("subjectType", d.subjectType().orElse(null));
    body.put("subjectId", d.subjectId().orElse(null));
    try {
      return json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("unserialisable push payload", e);
    }
  }

  private static String clip(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 3) + "...";
  }

  private void refreshDead() {
    try {
      dead.set(database.asSystem(ModuleRole.NOTIFICATION, repository::deadPushes));
    } catch (DataAccessException e) {
      metrics.increment("waypoint.notification.dead_count_failed");
    }
  }
}
