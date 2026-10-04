package com.waypoint.dispatch.notification.application;

import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.notification.domain.Delivery.Channel;
import com.waypoint.dispatch.notification.domain.Delivery.Status;
import com.waypoint.dispatch.notification.domain.NotificationPolicy;
import com.waypoint.dispatch.notification.domain.NotificationPolicy.Addressed;
import com.waypoint.dispatch.notification.domain.NotificationPolicy.Routing;
import com.waypoint.dispatch.notification.domain.NotificationPolicy.Unrouted;
import com.waypoint.dispatch.notification.domain.RoutedEvent;
import com.waypoint.dispatch.notification.domain.RoutedEvent.Subject;
import com.waypoint.dispatch.notification.domain.RoutingTable;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository.NewNotification;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository.Subscription;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns one routed event into notifications, inside the consumer's transaction
 * as the process.
 *
 * <p>Only the intent is written here: the inbox row (which is its own in-app
 * delivery) and a pending push per active subscription. Nothing leaves the
 * process inside this transaction; the push job sends after commit (R-NOT-05).
 * A redelivered event writes nothing new: each person's notification is unique
 * per event and target.
 */
@Component
public class Notifier {
  private static final Logger log = LoggerFactory.getLogger(Notifier.class);

  private final JdbcNotificationRepository repository;
  private final RoutingTables tables;
  private final IdentityQuery identity;
  private final PushGateway push;
  private final InboxSignals signals;
  private final Database database;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  Notifier(
      JdbcNotificationRepository repository,
      RoutingTables tables,
      IdentityQuery identity,
      PushGateway push,
      InboxSignals signals,
      Database database,
      Metrics metrics,
      Clock clock) {
    this.repository = repository;
    this.tables = tables;
    this.identity = identity;
    this.push = push;
    this.signals = signals;
    this.database = database;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** @return how many notifications were written */
  int notify(RoutedEvent event) {
    Instant now = clock.now();
    String type = event.eventType();
    RoutingTable table = tables.current();
    Routing routing =
        NotificationPolicy.route(
            event,
            table,
            (role, scope, scopeId, on) -> identity.recipientsFor(role, scope.code(), scopeId, on.orElse(null)));

    for (Unrouted u : routing.unrouted()) {
      // NOT-02: a scope nobody holds in that role. Counted and logged, never thrown,
      // so one unstaffed outlet cannot stall the queue for everyone else.
      metrics.increment("waypoint.notification.unrouted", "event", type, "role", u.role());
      log.info("No active {} holds {} {} for {}", u.role(), u.scope().code(), u.scopeId(), type);
    }

    boolean pushOn = push.config().enabled();
    Set<UUID> told = new LinkedHashSet<>();
    int written = 0;
    for (Addressed a : routing.notifications()) {
      UUID notificationId = UuidV7.generate(now, random);
      Optional<Subject> subject = a.target().subject();
      boolean inserted =
          repository.insert(
              new NewNotification(
                  notificationId, a.recipient(), event.eventId(), type, a.target().key(), table.version(),
                  a.title(), a.body(), subject.map(Subject::type), subject.map(Subject::id), now, a.facts()));
      if (!inserted) {
        metrics.increment("waypoint.notification.duplicate", "event", type);
        continue;
      }
      repository.insertDelivery(
          UuidV7.generate(now, random), notificationId, Channel.IN_APP, Optional.empty(), Status.DELIVERED,
          Optional.empty(), now);
      metrics.increment("waypoint.notification.delivery", "channel", "in_app", "status", "delivered");
      if (a.push()) {
        queuePush(notificationId, a.recipient(), pushOn, now);
      }
      for (String fact : a.missingFacts()) {
        metrics.increment("waypoint.notification.template_missing", "event", type, "fact", fact);
      }
      metrics.increment("waypoint.notification.created", "event", type, "role", a.role());
      told.add(a.recipient());
      written++;
    }
    if (!told.isEmpty()) {
      database.afterCommit(() -> signals.changed(told));
    }
    return written;
  }

  private void queuePush(UUID notificationId, UUID recipient, boolean pushOn, Instant now) {
    if (!pushOn) {
      metrics.increment("waypoint.notification.push_skipped", "reason", "not_configured");
      return;
    }
    for (Subscription s : repository.activeSubscriptions(recipient)) {
      repository.insertDelivery(
          UuidV7.generate(now, random), notificationId, Channel.PUSH, Optional.of(s.subscriptionId()),
          Status.PENDING, Optional.of(now), now);
      metrics.increment("waypoint.notification.delivery", "channel", "push", "status", "pending");
    }
  }
}
