package com.waypoint.dispatch.issues.contract;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What people and other modules see of an operational issue.
 *
 * <p>Every resolution carries an actor, an action and a reason (architecture
 * rule 8). A "loaded but not received" investigation is never auto-resolved
 * (R-RCP-07).
 */
public final class IssueViews {
  private IssueViews() {}

  public enum IssueType {
    LOADING_SHORTFALL,
    DAMAGED_GOODS,
    FAILED_DELIVERY,
    LATE_DELIVERY,
    VEHICLE_FAULT,
    ROAD_DISRUPTION,
    RECEIPT_DISPUTE,
    STOCK_DISCREPANCY,
    OTHER
  }

  public enum IssueSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
  }

  public enum IssueStatus {
    OPEN,
    ASSIGNED,
    RESOLVED,
    CLOSED,
    CANCELLED
  }

  /**
   * What an issue is about. References are by id across modules, never by
   * foreign key (decision D-B).
   *
   * @param type {@code order}, {@code trip}, {@code delivery}, {@code receipt},
   *     {@code shortfall} or {@code vehicle}
   */
  public record SubjectRef(String type, String id) {}

  public record IssueView(
      UUID issueId,
      IssueType type,
      IssueSeverity severity,
      IssueStatus status,
      String depotCode,
      Optional<String> outletId,
      List<SubjectRef> subjects,
      String description,
      Optional<UUID> assignee,
      Optional<String> resolutionAction,
      Optional<String> resolutionNote,
      UUID raisedBy,
      Instant raisedAt,
      Optional<Instant> resolvedAt,
      long rowVersion) {

    public IssueView {
      subjects = List.copyOf(subjects);
    }
  }

  /**
   * One change to an issue, with who made it, what they did and why (rule 8).
   *
   * @param action what was done: raised, assigned, resolved, escalated, closed, cancelled
   */
  public record IssueHistoryView(
      Optional<IssueStatus> from,
      IssueStatus to,
      String action,
      String reason,
      Optional<UUID> actorId,
      Instant at) {}
}
