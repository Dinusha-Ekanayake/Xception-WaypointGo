package com.waypoint.dispatch.messaging.contract;

import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What Messaging publishes. Notification turns it into inbox entries and push (R-NOT-14). */
public final class MessagingEvents {
  private MessagingEvents() {}

  /**
   * Someone wrote on a thread. Carries who it is for, so Notification tells only
   * them: {@code audience} is dispatch, driver, loader, outlet or all, and
   * {@code outletIds} the outlets it reaches (one for outlet, every stop for
   * all, none otherwise). The body is never in the event: only a short excerpt
   * for the notification, and the thread is the record.
   */
  public record MessagePosted(
      UUID threadId,
      UUID messageId,
      String subjectType,
      String subjectId,
      String depotCode,
      Optional<String> vehicleId,
      Optional<LocalDate> serviceDate,
      String authorName,
      String authorRole,
      String kind,
      Optional<String> reportType,
      String audience,
      List<String> outletIds,
      String excerpt,
      Instant at)
      implements DomainEvent {
    public static final String TYPE = "message.posted";

    public MessagePosted {
      outletIds = List.copyOf(outletIds);
    }

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "thread";
    }

    @Override
    public String aggregateId() {
      return threadId.toString();
    }
  }
}
