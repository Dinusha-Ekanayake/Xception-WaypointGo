package com.waypoint.dispatch.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.notification.application.PushGateway;
import com.waypoint.dispatch.notification.contract.NotificationCommands;
import com.waypoint.dispatch.notification.contract.NotificationViews.PushConfigView;
import com.waypoint.dispatch.notification.domain.Delivery.PushResult;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import com.waypoint.dispatch.support.TestDates;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Notification's integration tests: the people of {@link ReceiptIssuesSupport},
 * a push service that answers what the test tells it to, and reads of what was
 * written.
 */
@Import(NotificationSupport.FakePushConfig.class)
public abstract class NotificationSupport extends ReceiptIssuesSupport {

  @Autowired protected FakePush push;

  @TestConfiguration
  static class FakePushConfig {
    @Bean
    @Primary
    FakePush fakePush() {
      return new FakePush();
    }
  }

  /** A push service with no network: on unless switched off, answering ACCEPTED unless told otherwise. */
  public static class FakePush implements PushGateway {
    public volatile boolean enabled = true;
    public volatile PushResult answer = PushResult.ACCEPTED;
    public final List<Target> sent = Collections.synchronizedList(new ArrayList<>());

    @Override
    public PushConfigView config() {
      return enabled
          ? new PushConfigView(true, Optional.of("BTEST"), Optional.empty())
          : new PushConfigView(false, Optional.empty(), Optional.of("push is not configured on this server"));
    }

    @Override
    public Response send(Target target, byte[] payload, Duration ttl, boolean urgent) {
      sent.add(target);
      return new Response(answer, "fake " + answer);
    }

    void reset() {
      enabled = true;
      answer = PushResult.ACCEPTED;
      sent.clear();
    }
  }

  @AfterEach
  void resetPush() {
    push.reset();
  }

  // ---- reading what was written --------------------------------------------------

  protected List<Map<String, Object>> notificationsOf(UUID eventId) {
    return database.asSystem(
        ModuleRole.NOTIFICATION,
        () ->
            database.query(
                "SELECT notification_id, recipient_user_id, title, body, target_key, read_at, created_at"
                    + " FROM notification.notifications WHERE event_id = ? ORDER BY recipient_user_id, target_key",
                eventId));
  }

  protected List<UUID> recipientsOf(UUID eventId) {
    return notificationsOf(eventId).stream().map(r -> (UUID) r.get("recipient_user_id")).toList();
  }

  /**
   * What this event told one person. The test database is shared, and every test
   * class adds people to the same outlet and depot, so an event reaches all of
   * them: a test speaks only for the people it created.
   */
  protected Map<String, Object> notificationFor(UUID eventId, Person who) {
    return notificationsOf(eventId).stream()
        .filter(r -> who.id().equals(r.get("recipient_user_id")))
        .findFirst()
        .orElseThrow(() -> new AssertionError(who.email() + " was not notified of " + eventId));
  }

  protected UUID notificationIdFor(UUID eventId, Person who) {
    return (UUID) notificationFor(eventId, who).get("notification_id");
  }

  protected List<Map<String, Object>> deliveriesOf(UUID notificationId) {
    return database.asSystem(
        ModuleRole.NOTIFICATION,
        () ->
            database.query(
                "SELECT delivery_id, channel, status, attempts, last_error, subscription_id"
                    + " FROM notification.deliveries WHERE notification_id = ? ORDER BY channel",
                notificationId));
  }

  protected String subscriptionStatus(UUID subscriptionId) {
    return database.asSystem(
        ModuleRole.NOTIFICATION,
        () ->
            (String)
                database
                    .queryOne(
                        "SELECT status FROM notification.push_subscriptions WHERE subscription_id = ?",
                        subscriptionId)
                    .get("status"));
  }

  // ---- devices ---------------------------------------------------------------------

  protected static String endpoint() {
    return "https://push.test/send/" + UUID.randomUUID();
  }

  /** Keys of the right shapes; the fake push service never encrypts with them. */
  protected static String subscribeBody(String endpoint) {
    byte[] point = new byte[65];
    ThreadLocalRandom.current().nextBytes(point);
    point[0] = 4;
    byte[] auth = new byte[16];
    ThreadLocalRandom.current().nextBytes(auth);
    Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
    return envelope(
        NotificationCommands.SUBSCRIBE,
        null,
        """
        {"deviceId":"%s","endpoint":"%s","p256dhKey":"%s","authSecret":"%s"}
        """
            .formatted(UUID.randomUUID(), endpoint, b64.encodeToString(point), b64.encodeToString(auth)));
  }

  protected UUID subscribe(Person who, String endpoint) throws Exception {
    JsonNode ack = send(who, subscribeBody(endpoint), 200);
    return UUID.fromString(ack.at("/result/subscriptionId").asText());
  }

  // ---- drivers -------------------------------------------------------------------------

  /** Assigns {@code who} to a vehicle of the test depot on one date only, the way a driver gets scope. */
  protected String assignOn(Person who, LocalDate date) {
    String vehicle =
        database.asModule(
            ModuleRole.IAM,
            null,
            () ->
                (String)
                    Optional.ofNullable(
                            database.queryOne(
                                "SELECT v.vehicle_id FROM ref.vehicles v WHERE v.reference_version_id = ?"
                                    + " AND v.depot_code = ? AND NOT EXISTS (SELECT 1 FROM"
                                    + " iam.vehicle_driver_assignments a WHERE a.vehicle_id = v.vehicle_id"
                                    + " AND a.validity @> ?::date) ORDER BY v.vehicle_id LIMIT 1",
                                reference.currentVersionId().orElseThrow(),
                                depot,
                                java.sql.Date.valueOf(date)))
                        .orElseThrow(() -> new IllegalStateException("no free vehicle on " + date))
                        .get("vehicle_id"));
    database.asModule(
        ModuleRole.IAM,
        null,
        () ->
            database.update(
                "INSERT INTO iam.vehicle_driver_assignments (vehicle_id, driver_user_id, validity)"
                    + " VALUES (?, ?, daterange(?, ?))",
                vehicle,
                who.id(),
                java.sql.Date.valueOf(date),
                java.sql.Date.valueOf(date.plusDays(1))));
    return vehicle;
  }

  /** A date far enough out that no other test has assigned anyone to anything. */
  protected static LocalDate someFarDate() {
    return TestDates.unusedDay();
  }
}
