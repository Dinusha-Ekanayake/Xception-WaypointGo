package com.waypoint.dispatch.notification.application;

import com.waypoint.dispatch.notification.contract.NotificationQuery;
import com.waypoint.dispatch.notification.contract.NotificationViews.NotificationView;
import com.waypoint.dispatch.notification.infrastructure.JdbcNotificationRepository;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Reads inboxes. A person reads only their own.
 *
 * <p>Every read runs as {@code waypoint_notification} for the actor, so
 * row-level security narrows it in SQL to the actor's own rows (rule 7). Asking
 * for someone else's inbox is {@code 403} plus an audit row, never an empty
 * inbox and never the caller's own one instead.
 */
@Component
public class NotificationDataQuery implements NotificationQuery {
  public static final String READ = "notification:Read";

  private final Database database;
  private final JdbcNotificationRepository notifications;
  private final AuditLog audit;

  public NotificationDataQuery(Database database, JdbcNotificationRepository notifications, AuditLog audit) {
    this.database = database;
    this.notifications = notifications;
    this.audit = audit;
  }

  // ---- contract: as the ambient actor --------------------------------------

  @Override
  public Page<NotificationView> inbox(UUID userId, Optional<String> cursor, int limit) {
    return read(ambient(), () -> page(userId, cursor, limit));
  }

  @Override
  public long unreadCount(UUID userId) {
    return read(ambient(), () -> notifications.unreadCount(userId));
  }

  // ---- web: as the authenticated actor -------------------------------------

  public Page<NotificationView> inbox(Actor actor, Optional<UUID> owner, Optional<String> cursor, Integer limit) {
    requireOwn(actor, owner);
    return read(actor.userId(), () -> page(actor.userId(), cursor, Page.limit(limit)));
  }

  public long unreadCount(Actor actor, Optional<UUID> owner) {
    requireOwn(actor, owner);
    return unreadCountOf(actor.userId());
  }

  /** For the live badge: the person's own count, read as them. */
  long unreadCountOf(UUID userId) {
    return read(userId, () -> notifications.unreadCount(userId));
  }

  // ---- internals -----------------------------------------------------------

  /** An inbox is addressed only by being signed in; naming another person's is refused and recorded. */
  private void requireOwn(Actor actor, Optional<UUID> owner) {
    if (owner.isPresent() && !owner.get().equals(actor.userId())) {
      String resource = "wpt:notification:user:" + owner.get();
      String reason = "an inbox is readable only by its owner";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + ": " + reason);
    }
  }

  /** Newest first; the cursor is the last row's time and id, nothing personal. */
  private Page<NotificationView> page(UUID userId, Optional<String> cursor, int limit) {
    int size = Page.limit(limit);
    List<String> key = Cursor.decode(cursor.orElse(null), 2);
    Optional<Instant> created = Optional.empty();
    Optional<UUID> id = Optional.empty();
    if (!key.isEmpty()) {
      try {
        created = Optional.of(Instant.parse(key.get(0)));
        id = Optional.of(UUID.fromString(key.get(1)));
      } catch (IllegalArgumentException | DateTimeParseException e) {
        throw Cursor.invalid();
      }
    }
    List<NotificationView> rows = notifications.inbox(userId, created, id, size + 1);
    return Page.fromOverfetch(
        rows, size, v -> Cursor.encode(v.createdAt().toString(), v.notificationId().toString()));
  }

  private UUID ambient() {
    return database.ambientActor().orElse(null);
  }

  private <T> T read(UUID actorId, Supplier<T> work) {
    return database.readAs(ModuleRole.NOTIFICATION, actorId, work);
  }
}
