package com.waypoint.dispatch.issues.contract;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Payloads of Issues' commands. Any role may raise an issue at any stage (R-EXE-09). */
public final class IssueCommands {
  private IssueCommands() {}

  public static final String RAISE = "issue:Raise";
  public static final String ASSIGN = "issue:Assign";
  public static final String RESOLVE = "issue:Resolve";
  public static final String RECORD_REPLACEMENT = "issue:RecordReplacement";
  /** Binary, so not a command: the upload endpoint checks this action before it stores a photo. */
  public static final String ATTACH_PHOTO = "issue:AttachPhoto";
  public static final String SCHEDULE_REDELIVERY = "issue:ScheduleRedelivery";
  public static final String CLOSE = "issue:Close";
  public static final String CANCEL = "issue:Cancel";
  /** FF-03: Operations records what an inquiry found, with any write-off value and photos. */
  public static final String RECORD_INQUIRY = "issue:RecordInquiry";
  /** FF-03: Operations approves or rejects a recorded write-off, within P-DG-1. */
  public static final String APPROVE_WRITE_OFF = "issue:ApproveWriteOff";
  /**
   * FF-09: the dispatcher asks one store a question on a live trip (R-ISS-12):
   * {@code {"kind": "receive_late", "deliveryId": uuid, "receiveAt": instant}} or
   * {@code {"kind": "dock_help", "deliveryId": uuid (the store asked),
   * "aboutDeliveryId": uuid (the stop whose goods are in front)}}. Asking again
   * about the same stop withdraws the open question.
   */
  public static final String ASK_DECISION = "issue:AskDecision";
  /**
   * FF-09: the store answers (R-ISS-14): {@code {"requestId": uuid, "answer":
   * "yes" | "no", "reason": "optional, up to 500", "voiceNoteId": uuid (optional,
   * a voice reason posted on the trip's thread), "byPhone": true (the dispatcher
   * recording what the store said on a call)}}.
   */
  public static final String ANSWER_DECISION = "issue:AnswerDecision";

  /**
   * @param attachmentIds photos uploaded for the problem (issue:AttachPhoto), by id. A photo still on
   *     the phone may arrive after the issue; it is linked when it does.
   */
  public record RaiseIssue(
      IssueType type,
      IssueSeverity severity,
      String depotCode,
      Optional<String> outletId,
      List<SubjectRef> subjects,
      String description,
      List<UUID> attachmentIds) {

    public RaiseIssue {
      subjects = List.copyOf(subjects);
      attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
  }

  public record AssignIssue(UUID issueId, UUID assigneeUserId) {}

  public record ResolveIssue(UUID issueId, String action, String note) {}

  /** A replacement for a shortfall was loaded; Loading may recheck (shortfall.resolved). */
  public record RecordReplacement(UUID issueId, UUID tripId, UUID orderId, String note) {}

  /** Ordering creates a new order linked to the original (decision B17). */
  public record ScheduleRedelivery(
      UUID issueId, UUID orderId, LocalDate requestedDate, String note) {}

  public record CloseIssue(UUID issueId) {}

  public record CancelIssue(UUID issueId, String reason) {}
}
