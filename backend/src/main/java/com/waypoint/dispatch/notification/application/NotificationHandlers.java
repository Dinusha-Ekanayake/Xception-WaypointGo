package com.waypoint.dispatch.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.notification.contract.NotificationCommands;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository.Subscription;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A person's own inbox and devices. Each runs in the bus's transaction as
 * {@code waypoint_notification} for the actor, so row-level security limits it
 * to the actor's own notifications and subscriptions: another person's id is
 * simply not found.
 *
 * <p>Marking read is set-once (R-NOT-06): a row already read keeps the time it
 * was first read, and nothing ever makes it unread, so these take no
 * {@code expectedVersion}. A replayed command is answered from the bus receipt.
 */
final class NotificationHandlers {
  /** A single MarkRead is bounded like a page of the inbox. */
  static final int MAX_IDS = Page.MAX_LIMIT;

  private NotificationHandlers() {}

  abstract static class NotificationHandler implements CommandHandler {
    protected final JdbcNotificationRepository repository;
    protected final Database database;
    protected final InboxSignals signals;
    protected final Clock clock;

    NotificationHandler(JdbcNotificationRepository repository, Database database, InboxSignals signals, Clock clock) {
      this.repository = repository;
      this.database = database;
      this.signals = signals;
      this.clock = clock;
    }

    @Override
    public final String action() {
      return kind();
    }

    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.NOTIFICATION;
    }

    /** The badge of every tab this person has open moves once this commits. */
    protected Map<String, Object> marked(Actor actor, int count) {
      database.afterCommit(() -> signals.changed(List.of(actor.userId())));
      return Map.of("marked", count, "unread", repository.unreadCount(actor.userId()));
    }
  }

  @Component
  static class MarkRead extends NotificationHandler {
    MarkRead(JdbcNotificationRepository repository, Database database, InboxSignals signals, Clock clock) {
      super(repository, database, signals, clock);
    }

    @Override
    public String kind() {
      return NotificationCommands.MARK_READ;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      List<UUID> ids = ids(command);
      if (repository.visible(ids) < ids.size()) {
        throw new DomainException(ErrorCode.NOT_FOUND, "One or more notifications were not found");
      }
      return marked(actor, repository.markRead(ids, clock.now()));
    }

    private static List<UUID> ids(Command command) {
      JsonNode node = command.payload() == null ? null : command.payload().get("notificationIds");
      if (node == null || !node.isArray() || node.isEmpty()) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED,
            "notificationIds is required; use " + NotificationCommands.MARK_ALL_READ + " for everything");
      }
      if (node.size() > MAX_IDS) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "notificationIds takes at most " + MAX_IDS);
      }
      Set<UUID> ids = new LinkedHashSet<>();
      for (JsonNode id : node) {
        try {
          ids.add(UUID.fromString(id.asText()));
        } catch (IllegalArgumentException e) {
          throw new DomainException(ErrorCode.VALIDATION_FAILED, "notificationIds holds a non-uuid: " + id.asText());
        }
      }
      return new ArrayList<>(ids);
    }
  }

  @Component
  static class MarkAllRead extends NotificationHandler {
    MarkAllRead(JdbcNotificationRepository repository, Database database, InboxSignals signals, Clock clock) {
      super(repository, database, signals, clock);
    }

    @Override
    public String kind() {
      return NotificationCommands.MARK_ALL_READ;
    }

    /** Up to when the person looked, never later than now: a device clock ahead must not mark the future. */
    @Override
    public Object handle(Actor actor, Command command) {
      String text = CommandPayload.of(command).requiredText("upTo");
      Instant upTo;
      try {
        upTo = Instant.parse(text);
      } catch (DateTimeParseException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "upTo must be an ISO-8601 instant, not " + text);
      }
      Instant now = clock.now();
      Instant bound = upTo.isAfter(now) ? now : upTo;
      return marked(actor, repository.markAllRead(actor.userId(), bound, now));
    }
  }

  @Component
  static class SubscribePush extends NotificationHandler {
    private final PushGateway push;
    private final Metrics metrics;
    private final SecureRandom random = new SecureRandom();

    SubscribePush(
        JdbcNotificationRepository repository,
        Database database,
        InboxSignals signals,
        Clock clock,
        PushGateway push,
        Metrics metrics) {
      super(repository, database, signals, clock);
      this.push = push;
      this.metrics = metrics;
    }

    @Override
    public String kind() {
      return NotificationCommands.SUBSCRIBE;
    }

    /**
     * One browser has one endpoint, so it pushes to whoever subscribed it last: a
     * shared device that changes hands stops receiving the previous person's
     * notifications. Retiring the other person's row is done as the process, in
     * its own transaction, because row-level security hides it from the actor.
     */
    @Override
    public Object handle(Actor actor, Command command) {
      if (!push.config().enabled()) {
        throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE, "push is not configured on this server");
      }
      CommandPayload payload = CommandPayload.of(command);
      Optional<UUID> deviceId = Optional.ofNullable(payload.optionalUuid("deviceId"));
      String endpoint = endpoint(payload.requiredText("endpoint"));
      String p256dh = key(payload.requiredText("p256dhKey"), "p256dhKey", 65);
      String auth = key(payload.requiredText("authSecret"), "authSecret", 16);
      Instant now = clock.now();

      int handedOver =
          database.asSystemSeparately(
              ModuleRole.NOTIFICATION, () -> repository.retireEndpointOfOthers(endpoint, actor.userId(), now));
      if (handedOver > 0) {
        metrics.increment("waypoint.notification.subscription_handed_over");
      }
      Optional<Subscription> mine = repository.activeAt(endpoint);
      UUID subscriptionId;
      if (mine.isPresent()) {
        subscriptionId = mine.get().subscriptionId();
        repository.refreshKeys(subscriptionId, mine.get().rowVersion(), deviceId, p256dh, auth, now);
      } else {
        subscriptionId = UuidV7.generate(now, random);
        repository.insertSubscription(subscriptionId, actor.userId(), deviceId, endpoint, p256dh, auth, now);
        metrics.increment("waypoint.notification.subscribed");
      }
      return Map.of("subscriptionId", subscriptionId, "status", "active");
    }

    private static String endpoint(String text) {
      URI uri;
      try {
        uri = URI.create(text);
      } catch (IllegalArgumentException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "endpoint is not a URL");
      }
      if (!"https".equals(uri.getScheme()) || uri.getHost() == null || text.length() > 2048) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "endpoint must be an https URL a push service issued");
      }
      return text;
    }

    /** Base64url of exactly {@code bytes} bytes, the shapes RFC 8291 requires. */
    private static String key(String text, String field, int bytes) {
      byte[] raw;
      try {
        raw = Base64.getUrlDecoder().decode(text.replace('+', '-').replace('/', '_').replace("=", ""));
      } catch (IllegalArgumentException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is not base64url");
      }
      if (raw.length != bytes || (bytes == 65 && raw[0] != 4)) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is not a valid push subscription key");
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
  }

  @Component
  static class UnsubscribePush extends NotificationHandler {
    UnsubscribePush(JdbcNotificationRepository repository, Database database, InboxSignals signals, Clock clock) {
      super(repository, database, signals, clock);
    }

    @Override
    public String kind() {
      return NotificationCommands.UNSUBSCRIBE;
    }

    /** Never deleted, and unsubscribing what is already gone is harmless, so it says so rather than failing. */
    @Override
    public Object handle(Actor actor, Command command) {
      String endpoint = CommandPayload.of(command).requiredText("endpoint");
      Optional<Subscription> mine = repository.activeAt(endpoint);
      mine.ifPresent(s -> repository.endSubscription(s.subscriptionId(), "unsubscribed", clock.now()));
      return Map.of("unsubscribed", mine.isPresent());
    }
  }
}
