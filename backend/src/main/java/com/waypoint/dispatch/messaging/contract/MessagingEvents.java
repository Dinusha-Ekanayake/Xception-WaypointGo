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
      Instant at,
      Optional<UUID> voiceNoteId,
      Optional<Integer> voiceDurationMs,
      List<Integer> voicePeaks,
      /** The store a store manager wrote from, when they run one on the trip; a report's stop (R-ML-35). */
      Optional<String> aboutOutletId)
      implements DomainEvent {
    public static final String TYPE = "message.posted";

    /** As written before the store a message came from was carried. */
    public MessagePosted(
        UUID threadId, UUID messageId, String subjectType, String subjectId, String depotCode,
        Optional<String> vehicleId, Optional<LocalDate> serviceDate, String authorName, String authorRole, String kind,
        Optional<String> reportType, String audience, List<String> outletIds, String excerpt, Instant at,
        Optional<UUID> voiceNoteId, Optional<Integer> voiceDurationMs, List<Integer> voicePeaks) {
      this(threadId, messageId, subjectType, subjectId, depotCode, vehicleId, serviceDate, authorName, authorRole, kind,
          reportType, audience, outletIds, excerpt, at, voiceNoteId, voiceDurationMs, voicePeaks, Optional.empty());
    }

    public MessagePosted {
      outletIds = List.copyOf(outletIds);
      // Events written before a voice note was carried have none of these.
      voiceNoteId = voiceNoteId == null ? Optional.empty() : voiceNoteId;
      voiceDurationMs = voiceDurationMs == null ? Optional.empty() : voiceDurationMs;
      voicePeaks = voicePeaks == null ? List.of() : List.copyOf(voicePeaks);
      aboutOutletId = aboutOutletId == null ? Optional.empty() : aboutOutletId;
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

  /**
   * A voice note on a trip's thread was written down (R-MSG-15), so what was
   * said can be read like a typed message: by Intelligence for the trip's Next
   * step card (R-ML-35). Carries only the first line, as {@link MessagePosted}'s
   * excerpt, never the whole transcript.
   *
   * @param aboutOutletId as {@link MessagePosted#aboutOutletId()}
   */
  public record MessageTranscribed(
      UUID threadId,
      UUID messageId,
      String subjectType,
      String subjectId,
      String depotCode,
      String authorRole,
      String kind,
      Optional<String> reportType,
      Optional<String> aboutOutletId,
      String excerpt,
      Optional<String> language,
      Instant at)
      implements DomainEvent {
    public static final String TYPE = "message.transcribed";

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
