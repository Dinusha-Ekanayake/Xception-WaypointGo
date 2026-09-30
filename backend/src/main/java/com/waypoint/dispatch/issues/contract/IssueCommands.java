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
  public static final String SCHEDULE_REDELIVERY = "issue:ScheduleRedelivery";
  public static final String CLOSE = "issue:Close";
  public static final String CANCEL = "issue:Cancel";

  public record RaiseIssue(
      IssueType type,
      IssueSeverity severity,
      String depotCode,
      Optional<String> outletId,
      List<SubjectRef> subjects,
      String description) {

    public RaiseIssue {
      subjects = List.copyOf(subjects);
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
