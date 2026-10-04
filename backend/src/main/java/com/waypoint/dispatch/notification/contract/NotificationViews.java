package com.waypoint.dispatch.notification.contract;

import java.time.Instant;
import java.util.Map;
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
      Optional<Instant> readAt,
      // The values the title and body were filled from, so a client can show
      // the same message in its own language (issue #118). Empty for older rows.
      Map<String, String> facts) {}

  public record UnreadCountView(long count) {}

  /**
   * Whether this server can push, and the key a browser subscribes with. Off is
   * a state to show, not hide: the UI says push is unavailable and why (rule 9).
   *
   * @param publicKey the VAPID public key, base64url, when push is on
   * @param reason why push is off, when it is
   */
  public record PushConfigView(boolean enabled, Optional<String> publicKey, Optional<String> reason) {}
}
