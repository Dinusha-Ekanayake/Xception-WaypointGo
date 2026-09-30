package com.waypoint.dispatch.notification.contract;

import java.util.List;
import java.util.UUID;

/** Payloads of Notification's commands. Notification publishes no events. */
public final class NotificationCommands {
  private NotificationCommands() {}

  public static final String MARK_READ = "notification:MarkRead";
  public static final String SUBSCRIBE = "notification:Subscribe";
  public static final String UNSUBSCRIBE = "notification:Unsubscribe";

  /** An empty list marks every unread notification read. */
  public record MarkRead(List<UUID> notificationIds) {

    public MarkRead {
      notificationIds = List.copyOf(notificationIds);
    }
  }

  /** A browser push subscription, as the Push API returns it. */
  public record SubscribePush(UUID deviceId, String endpoint, String p256dhKey, String authSecret) {}

  public record UnsubscribePush(String endpoint) {}
}
