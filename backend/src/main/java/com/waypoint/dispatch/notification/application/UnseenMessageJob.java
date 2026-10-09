package com.waypoint.dispatch.notification.application;

import com.waypoint.dispatch.notification.domain.UnseenMessages;
import com.waypoint.dispatch.notification.domain.UnseenMessages.Unseen;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Tells the depot's dispatchers when a driver has not seen a message for ten
 * minutes (R-NOT-18, roadmap FF-04 escalation). The driver is often driving, and
 * the Messages screen is covered while moving (R-EXE-28), so the person who can
 * act is the dispatcher, by phone. Each driver's notification is told once: the
 * dispatchers' event takes its id.
 */
@Component
public class UnseenMessageJob implements ScheduledJob {
  static final int BATCH = 100;

  private final Database database;
  private final JdbcNotificationRepository repository;
  private final Notifier notifier;
  private final Metrics metrics;

  UnseenMessageJob(Database database, JdbcNotificationRepository repository, Notifier notifier, Metrics metrics) {
    this.database = database;
    this.repository = repository;
    this.notifier = notifier;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "notification.unseen-messages";
  }

  @Override
  public String cron() {
    return "0 * * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.NOTIFICATION;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many unseen messages were told */
  int runAt(Instant now) {
    List<Unseen> due =
        database.asSystem(
            ModuleRole.NOTIFICATION,
            () -> repository.unseenByDrivers(
                UnseenMessages.dueBefore(now), UnseenMessages.givenUpBefore(now), BATCH));
    for (Unseen u : due) {
      // Each in its own transaction, so one depot nobody staffs cannot hold up the rest.
      database.asSystem(
          ModuleRole.NOTIFICATION, () -> notifier.notify(UnseenMessages.event(u, now, Clock.OPERATING_ZONE)));
      metrics.increment("waypoint.notification.unseen_message");
    }
    return due.size();
  }
}
