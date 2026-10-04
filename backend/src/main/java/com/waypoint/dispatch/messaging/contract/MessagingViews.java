package com.waypoint.dispatch.messaging.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What {@code /api/threads} answers. Mirrored in frontend/src/shared/domain/messaging.ts. */
public final class MessagingViews {
  private MessagingViews() {}

  /**
   * One thread, and how the reader belongs to it.
   *
   * @param memberRole dispatcher, loader, driver or store_manager: the reader's
   *     part here, which decides who they may write to (R-MSG-02)
   * @param myOutlets the reader's own outlets among the subject's, for a store manager
   */
  public record ThreadView(
      UUID threadId,
      String subjectType,
      String subjectId,
      String depotCode,
      Optional<String> vehicleId,
      Optional<LocalDate> serviceDate,
      List<String> outletIds,
      String memberRole,
      List<String> myOutlets,
      boolean open) {

    public ThreadView {
      outletIds = List.copyOf(outletIds);
      myOutlets = List.copyOf(myOutlets);
    }
  }

  /**
   * One message the reader may see (R-MSG-01).
   *
   * @param authorName the person's name, or the role for a report made from an event
   * @param audience dispatch, driver, loader, outlet or all
   * @param voiceNoteId set for a voice message or report, played from
   *     {@code /api/threads/{threadId}/voice/{voiceNoteId}}
   * @param mine written by the reader
   * @param voicePeaks the voice note's waveform, 0 to 100 a bar, as the recording
   *     phone measured it; empty when unknown
   * @param resolvedAt when the dispatcher resolved this report (R-MSG-07)
   */
  public record MessageView(
      UUID messageId,
      UUID threadId,
      String authorName,
      String authorRole,
      String kind,
      Optional<String> reportType,
      String audience,
      Optional<String> audienceOutlet,
      String body,
      Optional<UUID> voiceNoteId,
      Optional<Integer> voiceDurationMs,
      Instant createdAt,
      boolean mine,
      List<Integer> voicePeaks,
      Optional<Instant> resolvedAt,
      Optional<String> resolvedByName,
      Optional<String> resolutionNote) {
    public MessageView {
      voicePeaks = List.copyOf(voicePeaks);
    }
  }

  /** A page of messages, newest first; {@code nextCursor} reads older ones. */
  public record MessagePage(List<MessageView> items, Optional<String> nextCursor) {
    public MessagePage {
      items = List.copyOf(items);
    }
  }

  /**
   * A report on a trip, for the warning signs on the dispatcher's timeline.
   *
   * @param outletId the stop the report is about, when known: the sign is drawn on it
   */
  public record ReportMarkView(
      UUID threadId,
      String tripId,
      Optional<String> vehicleId,
      UUID messageId,
      Instant at,
      String reportType,
      String authorRole,
      String excerpt,
      boolean voice,
      Optional<String> outletId) {}

  /**
   * Someone the reader may write to on this thread.
   *
   * @param to dispatch, driver, loader, outlet or all
   * @param outletId set when {@code to} is outlet
   * @param label what the screen shows: a name, "Loaders at Kandy", an outlet
   */
  public record MemberView(String to, Optional<String> outletId, String label) {}
}
