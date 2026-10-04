package com.waypoint.dispatch.notification.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of Notification's commands. Notification publishes no events.
 *
 * <p>Read state only moves from unread to read, so marking is set-once and takes
 * no {@code expectedVersion}: there is no earlier write to lose (R-NOT-06).
 */
public final class NotificationCommands {
  private NotificationCommands() {}

  public static final String MARK_READ = "notification:MarkRead";
  public static final String MARK_ALL_READ = "notification:MarkAllRead";
  public static final String SUBSCRIBE = "notification:Subscribe";
  public static final String UNSUBSCRIBE = "notification:Unsubscribe";

  /** At least one id, each the caller's own. */
  public record MarkRead(List<UUID> notificationIds) {

    public MarkRead {
      notificationIds = List.copyOf(notificationIds);
    }
  }

  /**
   * Every unread notification created at or before {@code upTo}, which is when
   * the person last looked. One that arrived after it stays unread.
   */
  public record MarkAllRead(Instant upTo) {}

  /** A browser push subscription, as the Push API returns it. */
  public record SubscribePush(UUID deviceId, String endpoint, String p256dhKey, String authSecret) {}

  public record UnsubscribePush(String endpoint) {}
}
