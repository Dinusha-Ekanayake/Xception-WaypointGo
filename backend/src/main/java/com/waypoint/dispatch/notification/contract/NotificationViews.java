package com.waypoint.dispatch.notification.contract;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a person sees in their inbox. Channels are the in-app inbox and web push
 * (decision D-N); every delivery attempt is tracked, so "the store was told" is
 * a fact the system can prove.
 */
public final class NotificationViews {
  private NotificationViews() {}

  /**
   * @param eventType the event that produced it, for example {@code order.deferred}
   * @param subjectType what it is about, for example {@code order}, so the UI can link to it
   */
  public record NotificationView(
      UUID notificationId,
      String eventType,
      String title,
      String body,
      Optional<String> subjectType,
      Optional<String> subjectId,
      Instant createdAt,
      Optional<Instant> readAt) {}
}
