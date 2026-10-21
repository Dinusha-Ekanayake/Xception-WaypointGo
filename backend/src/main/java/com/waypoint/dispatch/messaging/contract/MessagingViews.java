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
   * @param channelOpen the driver has started the trip, so its channel takes posts (R-MSG-08)
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
      boolean open,
      boolean channelOpen) {

    public ThreadView {
      outletIds = List.copyOf(outletIds);
      myOutlets = List.copyOf(myOutlets);
    }
  }

  /**
   * One message the reader may see (R-MSG-01).
   *
   * @param authorName the person's name, or the role for a report made from an event
   * @param audience dispatch, driver, loader, outlet, all or channel
   * @param voiceNoteId set for a voice message or report, played from
   *     {@code /api/threads/{threadId}/voice/{voiceNoteId}}
   * @param mine written by the reader
   * @param voicePeaks the voice note's waveform, 0 to 100 a bar, as the recording
   *     phone measured it; empty when unknown
   * @param resolvedAt when the dispatcher resolved this report (R-MSG-07)
   * @param redirectedAt when the dispatcher moved this post off the trip's
   *     channel (R-MSG-08); stores other than its author no longer see it
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
      Optional<String> resolutionNote,
      Optional<Instant> redirectedAt,
      Optional<String> redirectReason,
      /**
       * R-MSG-14, for the dispatcher only: whether the driver has read this
       * message for the driver or for everyone, and when. Empty when unknown or
       * not for the driver.
       */
      Optional<Boolean> seenByDriver,
      Optional<Instant> seenByDriverAt,
      /** FF-05: a voice note's machine transcript; empty for a typed message. */
      Optional<TranscriptView> transcript,
      /**
       * The issue a report was made from (R-MSG-05). Resolving that report is
       * resolving the issue (R-MSG-07), so the dispatcher's screen acts on the
       * issue; empty for a message a person wrote.
       */
      Optional<UUID> issueId,
      /**
       * The stop a report is about (a store's own outlet, or the issue's), so the
       * dispatcher can act on that stop beside the report (R-EXE-31); empty for a
       * message, or a report about no stop.
       */
      Optional<String> aboutOutletId) {
    public MessageView {
      voicePeaks = List.copyOf(voicePeaks);
    }
  }

  /**
   * FF-05: what a transcription engine heard in a voice note, labelled as a
   * machine transcript on screen.
   *
   * @param status {@code pending} while it is being written down, {@code done}
   *     with the words, or {@code none} when there is no transcript (no engine,
   *     the engine failed, or the words were cleared with the audio): play the audio
   * @param language en, si or ta, as the engine named it
   */
  public record TranscriptView(String status, Optional<String> text, Optional<String> language) {}

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
   * @param to dispatch, driver, loader, outlet, all or channel
   * @param outletId set when {@code to} is outlet
   * @param label what the screen shows: a name, "Loaders at Kandy", an outlet
   */
  public record MemberView(String to, Optional<String> outletId, String label) {}
}
