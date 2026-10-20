package com.waypoint.dispatch.notification.application;

import com.waypoint.dispatch.demo.contract.DemoEvents.DriverNotificationsRequested;
import com.waypoint.dispatch.notification.domain.DemoSamples;
import com.waypoint.dispatch.notification.domain.Delivery.Channel;
import com.waypoint.dispatch.notification.domain.Delivery.Status;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository.NewNotification;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Writes the demo driver's sample notices (demo.driver_notifications_requested)
 * into their inbox, in app only, never as a push. One request is one event, so a
 * redelivered event writes nothing twice (the notifications' own uniqueness on
 * event, recipient and target); a second request writes a fresh set.
 */
@Component
class DemoDriverNotifications implements EventSubscriber<DriverNotificationsRequested> {
  private final JdbcNotificationRepository repository;
  private final InboxSignals signals;
  private final Database database;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  DemoDriverNotifications(JdbcNotificationRepository repository, InboxSignals signals, Database database,
      Metrics metrics, Clock clock) {
    this.repository = repository;
    this.signals = signals;
    this.database = database;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override public String consumerName() { return "notification.on-demo-driver-notifications"; }
  @Override public Class<DriverNotificationsRequested> eventType() { return DriverNotificationsRequested.class; }
  @Override public ModuleRole moduleRole() { return ModuleRole.NOTIFICATION; }

  @Override
  public void on(EventEnvelope<DriverNotificationsRequested> envelope) {
    DriverNotificationsRequested request = envelope.payload();
    int version = repository.currentRoutingVersion()
        .orElseThrow(() -> new IllegalStateException("no current notification routing version"));
    Instant now = clock.now();
    int written = 0;
    for (DemoSamples.Sample sample : DemoSamples.forDay(request.serviceDate())) {
      var id = UuidV7.generate(now, random);
      boolean inserted = repository.insert(new NewNotification(id, request.driverUserId(), envelope.eventId(),
          sample.eventType(), sample.key(), version, sample.title(), sample.body(), Optional.empty(), Optional.empty(),
          now, Map.of("demo", "true")));
      if (!inserted) continue;
      repository.insertDelivery(UuidV7.generate(now, random), id, Channel.IN_APP, Optional.empty(), Status.DELIVERED,
          Optional.empty(), now);
      written++;
    }
    metrics.increment("waypoint.notification.demo_samples", "written", String.valueOf(written));
    if (written > 0) database.afterCommit(() -> signals.changed(List.of(request.driverUserId())));
  }
}
