package com.waypoint.dispatch.issues.contract;

import java.time.Instant;
import java.time.LocalDate;
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
      long rowVersion,
      List<AttachmentView> attachments) {

    public IssueView {
      subjects = List.copyOf(subjects);
      attachments = List.copyOf(attachments);
    }
  }

  /**
   * A photo of the problem that has reached the server. Its bytes are read from
   * {@code /api/issues/{issueId}/attachments/{attachmentId}/content} by someone who
   * can see the issue; a photo past its retention is no longer listed.
   */
  public record AttachmentView(UUID attachmentId, String contentType) {}

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

  /**
   * FF-03: a write-off recorded by an inquiry, and its decision. {@code needsHead}
   * says the Head of Distribution must approve it (above the depot limit, or a Tech line).
   */
  public record WriteOffView(
      UUID writeOffId,
      UUID issueId,
      String outcome,
      java.math.BigDecimal valueLkr,
      boolean techLine,
      boolean needsHead,
      List<UUID> attachmentIds,
      UUID recordedBy,
      Instant recordedAt,
      String status,
      Optional<UUID> decidedBy,
      Optional<Instant> decidedAt,
      Optional<String> decisionNote,
      long rowVersion) {}

  /**
   * FF-09: a question asked of one store on a live trip, and its answer. The
   * dispatcher reads the depot's; a store reads its own outlet's.
   *
   * @param kind receive_late or dock_help
   * @param state sent, answered, expired or withdrawn
   * @param aboutUnits units of the stop whose goods are in front, for dock_help
   * @param arrivalAt when the vehicle was expected at the store when asked
   */
  public record DecisionView(
      UUID requestId,
      String kind,
      String depotCode,
      LocalDate serviceDate,
      UUID tripId,
      String vehicleId,
      UUID deliveryId,
      String outletId,
      Optional<UUID> aboutDeliveryId,
      Optional<String> aboutOutletId,
      Optional<Integer> aboutUnits,
      Optional<Instant> receiveAt,
      Optional<Instant> arrivalAt,
      Instant deadline,
      String state,
      Optional<String> answer,
      Optional<String> answeredVia,
      Optional<Instant> answeredAt,
      boolean answeredLate,
      Optional<String> reason,
      Optional<UUID> voiceNoteId,
      Instant askedAt,
      long rowVersion) {}
}
