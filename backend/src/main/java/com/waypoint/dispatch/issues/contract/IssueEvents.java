package com.waypoint.dispatch.issues.contract;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.shared.event.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Events Issues publishes. Consumers: Notification, Loading, Ordering. */
public final class IssueEvents {
  private IssueEvents() {}

  public record IssueRaised(
      UUID issueId,
      IssueType issueType,
      IssueSeverity severity,
      String depotCode,
      Optional<String> outletId,
      List<SubjectRef> subjects)
      implements DomainEvent {
    public static final String TYPE = "issue.raised";

    public IssueRaised {
      subjects = List.copyOf(subjects);
    }

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "issue";
    }

    @Override
    public String aggregateId() {
      return issueId.toString();
    }
  }

  public record IssueResolved(
      UUID issueId, String depotCode, Optional<String> outletId, String action, Instant at)
      implements DomainEvent {
    public static final String TYPE = "issue.resolved";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "issue";
    }

    @Override
    public String aggregateId() {
      return issueId.toString();
    }
  }

  /** Loading may recheck the order and release the trip. */
  public record ShortfallResolved(UUID issueId, UUID tripId, UUID orderId, String resolution)
      implements DomainEvent {
    public static final String TYPE = "shortfall.resolved";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "issue";
    }

    @Override
    public String aggregateId() {
      return issueId.toString();
    }
  }

  /** Ordering creates a new order linked to {@code originalOrderId}. */
  public record RedeliveryRequested(
      UUID issueId, UUID originalOrderId, LocalDate requestedDate, String reason)
      implements DomainEvent {
    public static final String TYPE = "redelivery.requested";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "issue";
    }

    @Override
    public String aggregateId() {
      return issueId.toString();
    }
  }
}
