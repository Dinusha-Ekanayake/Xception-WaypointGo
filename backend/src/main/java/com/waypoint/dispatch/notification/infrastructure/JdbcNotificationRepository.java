package com.waypoint.dispatch.notification.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.notification.contract.NotificationViews.NotificationView;
import com.waypoint.dispatch.notification.domain.Delivery;
import com.waypoint.dispatch.notification.domain.Delivery.Channel;
import com.waypoint.dispatch.notification.domain.Delivery.Outcome;
import com.waypoint.dispatch.notification.domain.Delivery.Status;
import com.waypoint.dispatch.notification.domain.RoutingRule;
import com.waypoint.dispatch.notification.domain.RoutingTable;
import com.waypoint.dispatch.notification.domain.ScopeKind;
import com.waypoint.dispatch.platform.db.Database;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Notifications, their deliveries and push subscriptions in PostgreSQL. Always
 * called inside a transaction someone else opened, as
 * {@code waypoint_notification}, so row-level security has already narrowed
 * every statement here to the actor's own rows, or to all of them for the
 * process.
 */
@Repository
public class JdbcNotificationRepository {
  private final Database database;
  private final ObjectMapper mapper;

  public JdbcNotificationRepository(Database database, ObjectMapper mapper) {
    this.database = database;
    this.mapper = mapper;
  }

  // ---- routing ---------------------------------------------------------------

  public Optional<Integer> currentRoutingVersion() {
    Map<String, Object> row =
        database.queryOne("SELECT rule_version FROM notification.routing_versions WHERE is_current");
    return row == null ? Optional.empty() : Optional.of(((Number) row.get("rule_version")).intValue());
  }

  public RoutingTable routing(int version) {
    List<RoutingRule> rules = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT event_type, recipient_role, scope_kind, push, when_fact, when_values, only_if_none_for,"
                + " title_template, body_template FROM notification.routing_rules WHERE rule_version = ?"
                + " ORDER BY event_type, recipient_role",
            version)) {
      rules.add(
          new RoutingRule(
              (String) row.get("event_type"),
              (String) row.get("recipient_role"),
              ScopeKind.parse((String) row.get("scope_kind")),
              (Boolean) row.get("push"),
              Optional.ofNullable((String) row.get("when_fact")),
              Set.copyOf(strings(row.get("when_values"))),
              Optional.ofNullable((String) row.get("only_if_none_for")),
              (String) row.get("title_template"),
              (String) row.get("body_template")));
    }
    return new RoutingTable(version, rules);
  }

  // ---- notifications ---------------------------------------------------------

  public record NewNotification(
      UUID notificationId,
      UUID recipient,
      UUID eventId,
      String eventType,
      String targetKey,
      int ruleVersion,
      String title,
      String body,
      Optional<String> subjectType,
      Optional<String> subjectId,
      Instant createdAt,
      Map<String, String> facts) {}

  /** @return false when this person already heard about this target of this event */
  public boolean insert(NewNotification n) {
    return database.update(
            """
            INSERT INTO notification.notifications
                (notification_id, recipient_user_id, event_id, event_type, target_key, rule_version, title, body,
                 subject_type, subject_id, created_at, facts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (event_id, recipient_user_id, target_key) DO NOTHING
            """,
            n.notificationId(),
            n.recipient(),
            n.eventId(),
            n.eventType(),
            n.targetKey(),
            n.ruleVersion(),
            n.title(),
            n.body(),
            n.subjectType().orElse(null),
            n.subjectId().orElse(null),
            Timestamp.from(n.createdAt()),
            json(n.facts()))
        == 1;
  }

  /** Newest first on the keyset {@code (created_at, notification_id)}; never OFFSET. */
  public List<NotificationView> inbox(
      UUID recipient, Optional<Instant> beforeCreated, Optional<UUID> beforeId, int limit) {
    String columns =
        "SELECT notification_id, event_type, title, body, subject_type, subject_id, created_at, read_at, facts::text AS facts"
            + " FROM notification.notifications WHERE recipient_user_id = ?";
    List<Map<String, Object>> rows =
        beforeCreated.isEmpty()
            ? database.query(columns + " ORDER BY created_at DESC, notification_id DESC LIMIT ?", recipient, limit)
            : database.query(
                columns
                    + " AND (created_at, notification_id) < (?, ?)"
                    + " ORDER BY created_at DESC, notification_id DESC LIMIT ?",
                recipient,
                Timestamp.from(beforeCreated.get()),
                beforeId.orElseThrow(),
                limit);
    return rows.stream().map(this::view).toList();
  }

  public long unreadCount(UUID recipient) {
    return ((Number)
            database
                .queryOne(
                    "SELECT count(*) AS n FROM notification.notifications"
                        + " WHERE recipient_user_id = ? AND read_at IS NULL",
                    recipient)
                .get("n"))
        .longValue();
  }

  /** How many of these ids the actor can see: anything else is not theirs, or does not exist. */
  public long visible(List<UUID> ids) {
    return ((Number)
            database
                .queryOne(
                    "SELECT count(*) AS n FROM notification.notifications WHERE notification_id = ANY (?::uuid[])",
                    (Object) ids(ids))
                .get("n"))
        .longValue();
  }

  /** Set-once: a row already read keeps the time it was first read (R-NOT-06). */
  public int markRead(List<UUID> ids, Instant at) {
    return database.update(
        "UPDATE notification.notifications SET read_at = ?, row_version = row_version + 1"
            + " WHERE notification_id = ANY (?::uuid[]) AND read_at IS NULL",
        Timestamp.from(at),
        ids(ids));
  }

  public int markAllRead(UUID recipient, Instant upTo, Instant at) {
    return database.update(
        "UPDATE notification.notifications SET read_at = ?, row_version = row_version + 1"
            + " WHERE recipient_user_id = ? AND read_at IS NULL AND created_at <= ?",
        Timestamp.from(at),
        recipient,
        Timestamp.from(upTo));
  }

  // ---- deliveries --------------------------------------------------------------

  public void insertDelivery(
      UUID deliveryId,
      UUID notificationId,
      Channel channel,
      Optional<UUID> subscriptionId,
      Status status,
      Optional<Instant> nextAttemptAt,
      Instant at) {
    database.update(
        """
        INSERT INTO notification.deliveries
            (delivery_id, notification_id, channel, subscription_id, status, next_attempt_at, created_at, sent_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT ON CONSTRAINT uq_deliveries_target DO NOTHING
        """,
        deliveryId,
        notificationId,
        channel.code(),
        subscriptionId.orElse(null),
        status.code(),
        nextAttemptAt.map(Timestamp::from).orElse(null),
        Timestamp.from(at),
        status == Status.DELIVERED ? Timestamp.from(at) : null);
  }

  /** A push due now, with what it carries and where it goes. */
  public record DuePush(
      UUID deliveryId,
      long rowVersion,
      int attempts,
      UUID notificationId,
      String eventType,
      String title,
      String body,
      Optional<String> subjectType,
      Optional<String> subjectId,
      Instant notificationCreatedAt,
      UUID subscriptionId,
      String subscriptionStatus,
      String endpoint,
      String p256dhKey,
      String authSecret) {}

  /**
   * Claims due pushes for this instance: locked so two instances never take the
   * same row, then leased by moving {@code next_attempt_at} past the send, so a
   * push being sent outside any transaction is not claimed again meanwhile.
   */
  public List<DuePush> claimDue(Instant now, Instant leaseUntil, int limit) {
    List<DuePush> due = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            """
            SELECT d.delivery_id, d.row_version, d.attempts, n.notification_id, n.event_type, n.title, n.body,
                   n.subject_type, n.subject_id, n.created_at, s.subscription_id, s.status AS subscription_status,
                   s.endpoint, s.p256dh_key, s.auth_secret
              FROM notification.deliveries d
              JOIN notification.notifications n ON n.notification_id = d.notification_id
              JOIN notification.push_subscriptions s ON s.subscription_id = d.subscription_id
             WHERE d.channel = 'push' AND d.status IN ('pending','failed') AND d.next_attempt_at <= ?
             ORDER BY d.next_attempt_at
             LIMIT ?
             FOR UPDATE OF d SKIP LOCKED
            """,
            Timestamp.from(now),
            limit)) {
      due.add(
          new DuePush(
              (UUID) row.get("delivery_id"),
              ((Number) row.get("row_version")).longValue() + 1,
              ((Number) row.get("attempts")).intValue(),
              (UUID) row.get("notification_id"),
              (String) row.get("event_type"),
              (String) row.get("title"),
              (String) row.get("body"),
              Optional.ofNullable((String) row.get("subject_type")),
              Optional.ofNullable((String) row.get("subject_id")),
              instant(row.get("created_at")),
              (UUID) row.get("subscription_id"),
              (String) row.get("subscription_status"),
              (String) row.get("endpoint"),
              (String) row.get("p256dh_key"),
              (String) row.get("auth_secret")));
      database.update(
          "UPDATE notification.deliveries SET next_attempt_at = ?, row_version = row_version + 1"
              + " WHERE delivery_id = ?",
          Timestamp.from(leaseUntil),
          row.get("delivery_id"));
    }
    return due;
  }

  /** Writes the attempt's outcome over the claimed row; a version mismatch means another instance took it. */
  public void recordAttempt(UUID deliveryId, long expectedVersion, Outcome outcome, Optional<String> error, Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE notification.deliveries
           SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ?, sent_at = ?,
               row_version = row_version + 1
         WHERE delivery_id = ? AND row_version = ?
        """,
        outcome.status().code(),
        outcome.attempts(),
        outcome.nextAttemptAt().map(Timestamp::from).orElse(null),
        error.map(e -> e.length() > 500 ? e.substring(0, 500) : e).orElse(null),
        outcome.status() == Status.SENT ? Timestamp.from(at) : null,
        deliveryId,
        expectedVersion);
  }

  public record DeliveryRow(UUID deliveryId, Channel channel, Status status, int attempts, Optional<String> lastError) {}

  public List<DeliveryRow> deliveriesOf(UUID notificationId) {
    return database
        .query(
            "SELECT delivery_id, channel, status, attempts, last_error FROM notification.deliveries"
                + " WHERE notification_id = ? ORDER BY channel, delivery_id",
            notificationId)
        .stream()
        .map(
            row ->
                new DeliveryRow(
                    (UUID) row.get("delivery_id"),
                    Channel.valueOf(((String) row.get("channel")).toUpperCase(java.util.Locale.ROOT)),
                    Delivery.Status.parse((String) row.get("status")),
                    ((Number) row.get("attempts")).intValue(),
                    Optional.ofNullable((String) row.get("last_error"))))
        .toList();
  }

  public long deadPushes() {
    return ((Number)
            database
                .queryOne("SELECT count(*) AS n FROM notification.deliveries WHERE status = 'dead'")
                .get("n"))
        .longValue();
  }

  // ---- push subscriptions --------------------------------------------------------

  public record Subscription(UUID subscriptionId, UUID userId, String endpoint, String status, long rowVersion) {}

  public List<Subscription> activeSubscriptions(UUID userId) {
    return database
        .query(
            "SELECT subscription_id, user_id, endpoint, status, row_version FROM notification.push_subscriptions"
                + " WHERE user_id = ? AND status = 'active' ORDER BY created_at, subscription_id",
            userId)
        .stream()
        .map(JdbcNotificationRepository::subscription)
        .toList();
  }

  /** The active subscription at an endpoint, if the actor can see it. */
  public Optional<Subscription> activeAt(String endpoint) {
    return database
        .query(
            "SELECT subscription_id, user_id, endpoint, status, row_version FROM notification.push_subscriptions"
                + " WHERE endpoint = ? AND status = 'active'",
            endpoint)
        .stream()
        .findFirst()
        .map(JdbcNotificationRepository::subscription);
  }

  public void insertSubscription(
      UUID subscriptionId, UUID userId, Optional<UUID> deviceId, String endpoint, String p256dh, String auth, Instant at) {
    database.update(
        """
        INSERT INTO notification.push_subscriptions
            (subscription_id, user_id, device_id, endpoint, p256dh_key, auth_secret, status, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?)
        """,
        subscriptionId,
        userId,
        deviceId.orElse(null),
        endpoint,
        p256dh,
        auth,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** A browser rotates its keys without changing its endpoint; the newest keys win. */
  public void refreshKeys(UUID subscriptionId, long expectedVersion, Optional<UUID> deviceId, String p256dh, String auth, Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE notification.push_subscriptions
           SET p256dh_key = ?, auth_secret = ?, device_id = coalesce(?, device_id), updated_at = ?,
               row_version = row_version + 1
         WHERE subscription_id = ? AND row_version = ?
        """,
        p256dh,
        auth,
        deviceId.orElse(null),
        Timestamp.from(at),
        subscriptionId,
        expectedVersion);
  }

  /** Active to {@code unsubscribed} or {@code expired}; a subscription is never deleted. */
  public int endSubscription(UUID subscriptionId, String status, Instant at) {
    return database.update(
        "UPDATE notification.push_subscriptions SET status = ?, updated_at = ?, row_version = row_version + 1"
            + " WHERE subscription_id = ? AND status = 'active'",
        status,
        Timestamp.from(at),
        subscriptionId);
  }

  /**
   * As the process: the endpoint is active for someone other than {@code userId}.
   * One browser has one endpoint, so whoever subscribes it now is who it pushes to.
   */
  public int retireEndpointOfOthers(String endpoint, UUID userId, Instant at) {
    return database.update(
        "UPDATE notification.push_subscriptions SET status = 'unsubscribed', updated_at = ?,"
            + " row_version = row_version + 1 WHERE endpoint = ? AND status = 'active' AND user_id <> ?",
        Timestamp.from(at),
        endpoint,
        userId);
  }

  // ---- mapping -------------------------------------------------------------------

  private String json(Map<String, String> facts) {
    try {
      return mapper.writeValueAsString(facts);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Notification facts are not serialisable", e);
    }
  }

  private Map<String, String> facts(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    try {
      return mapper.readValue(raw.toString(), new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }

  private NotificationView view(Map<String, Object> row) {
    return new NotificationView(
        (UUID) row.get("notification_id"),
        (String) row.get("event_type"),
        (String) row.get("title"),
        (String) row.get("body"),
        Optional.ofNullable((String) row.get("subject_type")),
        Optional.ofNullable((String) row.get("subject_id")),
        instant(row.get("created_at")),
        Optional.ofNullable(row.get("read_at")).map(JdbcNotificationRepository::instant),
        facts(row.get("facts")));
  }

  private static Subscription subscription(Map<String, Object> row) {
    return new Subscription(
        (UUID) row.get("subscription_id"),
        (UUID) row.get("user_id"),
        (String) row.get("endpoint"),
        (String) row.get("status"),
        ((Number) row.get("row_version")).longValue());
  }

  /** As text, cast to {@code uuid[]} in the statement: the driver binds a String[] everywhere. */
  private static String[] ids(List<UUID> ids) {
    return ids.stream().map(UUID::toString).toArray(String[]::new);
  }

  private static List<String> strings(Object value) {
    if (value == null) {
      return List.of();
    }
    try {
      return Arrays.asList((String[]) ((Array) value).getArray());
    } catch (SQLException e) {
      throw new IllegalStateException("unreadable routing condition", e);
    }
  }

  public static Instant instant(Object value) {
    if (value instanceof Timestamp t) {
      return t.toInstant();
    }
    if (value instanceof OffsetDateTime o) {
      return o.toInstant();
    }
    throw new IllegalStateException("not a timestamp: " + value);
  }
}
