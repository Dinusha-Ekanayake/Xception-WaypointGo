package com.waypoint.dispatch.notification.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.messaging.contract.MessagingEvents.MessagePosted;
import com.waypoint.dispatch.notification.NotificationSupport;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** R-NOT-18: a message the driver has not seen tells the depot's dispatchers, once. */
class UnseenMessageJobIntegrationTest extends NotificationSupport {
  @Autowired UnseenMessageJob job;

  private UUID toDriver(String vehicle, LocalDate date, String audience, String excerpt) {
    return deliver(
        "notification.on-message-posted",
        new MessagePosted(
            UUID.randomUUID(), UUID.randomUUID(), "trip", UUID.randomUUID().toString(), depot, Optional.of(vehicle),
            Optional.of(date), "Dinusha Bawantha", "dispatcher", "message", Optional.empty(), audience, List.of(),
            excerpt, Instant.now(), Optional.empty(), Optional.empty(), List.of()),
        dispatcher.id());
  }

  private Instant createdAt(UUID notificationId) {
    return database.asSystem(
        ModuleRole.NOTIFICATION,
        () -> JdbcNotificationRepository.instant(
            database.queryOne(
                    "SELECT created_at FROM notification.notifications WHERE notification_id = ?", notificationId)
                .get("created_at")));
  }

  @Test
  void aMessageTheDriverHasNotSeenForTenMinutesTellsTheDepotsDispatchersOnce() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    UUID posted = toDriver(vehicle, date, "driver", "Take the bypass at Akuressa");
    UUID driversNote = notificationIdFor(posted, driver);
    Instant created = createdAt(driversNote);

    job.runAt(created.plus(Duration.ofMinutes(5)));
    assertTrue(notificationsOf(driversNote).isEmpty(), "not before ten minutes");

    job.runAt(created.plus(Duration.ofMinutes(11)));
    Map<String, Object> told = notificationFor(driversNote, dispatcher);
    assertEquals(vehicle + ": the driver has not seen a message", told.get("title"));
    assertTrue(((String) told.get("body")).contains("Take the bypass at Akuressa"), (String) told.get("body"));
    assertTrue(((String) told.get("body")).contains("Unread 11 min"), (String) told.get("body"));
    assertFalse(recipientsOf(driversNote).contains(farDispatcher.id()), "another depot's dispatcher");
    assertFalse(recipientsOf(driversNote).contains(driver.id()));

    int before = notificationsOf(driversNote).size();
    job.runAt(created.plus(Duration.ofMinutes(20)));
    assertEquals(before, notificationsOf(driversNote).size(), "told once");
  }

  @Test
  void aMessageTheDriverReadOrOneTooOldOrNotForTheDriverTellsNobody() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    UUID read = notificationIdFor(toDriver(vehicle, date, "driver", "Read it"), driver);
    database.asSystem(
        ModuleRole.NOTIFICATION,
        () -> database.update(
            "UPDATE notification.notifications SET read_at = created_at WHERE notification_id = ?", read));
    UUID old = notificationIdFor(toDriver(vehicle, date, "all", "Long ago"), driver);
    Instant created = createdAt(old);

    job.runAt(created.plus(Duration.ofMinutes(11)));
    assertTrue(notificationsOf(read).isEmpty(), "the driver read it");

    UUID stale = notificationIdFor(toDriver(vehicle, date, "driver", "Stale"), driver);
    job.runAt(createdAt(stale).plus(Duration.ofHours(4)));
    assertTrue(notificationsOf(stale).isEmpty(), "past three hours the day has moved on");
  }
}
