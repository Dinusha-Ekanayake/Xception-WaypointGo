package com.waypoint.dispatch.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A message the driver has not seen (R-NOT-18). A driver's notification of a
 * message for the driver or for everyone, still unread after {@link #AFTER},
 * tells the depot's dispatchers once, so they can phone. Unread past
 * {@link #GIVE_UP} it is left alone: the day has moved on. Pure; the time is a
 * parameter.
 */
public final class UnseenMessages {
  public static final String EVENT_TYPE = "message.unseen";
  public static final Duration AFTER = Duration.ofMinutes(10);
  public static final Duration GIVE_UP = Duration.ofHours(3);
  /** The audiences a driver is told about: the dispatcher's or a loader's word to the driver, or to everyone. */
  public static final List<String> AUDIENCES = List.of("driver", "all");

  private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

  private UnseenMessages() {}

  /** One driver's notification of one message, unread. */
  public record Unseen(
      UUID notificationId,
      Instant createdAt,
      String depotCode,
      Optional<String> vehicleId,
      String heading,
      String excerpt,
      Optional<String> threadId) {}

  /** Notifications created at or before this are due. */
  public static Instant dueBefore(Instant now) {
    return now.minus(AFTER);
  }

  /** Notifications created at or before this are no longer told. */
  public static Instant givenUpBefore(Instant now) {
    return now.minus(GIVE_UP);
  }

  /**
   * The dispatchers' event. Its id is the driver's notification's own, so it is
   * told once however often the job looks.
   */
  public static RoutedEvent event(Unseen u, Instant now, ZoneId zone) {
    long minutes = Duration.between(u.createdAt(), now).toMinutes();
    Map<String, String> facts =
        Map.of(
            "vehicleId", u.vehicleId().orElse("The trip"),
            "heading", u.heading(),
            "excerpt", u.excerpt().isBlank() ? "Voice message" : u.excerpt(),
            "sent", HOUR_MINUTE.format(u.createdAt().atZone(zone)),
            "minutes", String.valueOf(minutes));
    Optional<RoutedEvent.Subject> subject = u.threadId().map(t -> new RoutedEvent.Subject("thread", t));
    return new RoutedEvent(
        u.notificationId(), EVENT_TYPE, Optional.empty(), Optional.empty(), facts,
        List.of(RoutedEvent.Target.of(ScopeKind.DEPOT, u.depotCode(), subject)));
  }
}
