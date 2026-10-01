package com.waypoint.dispatch.issues.domain;

import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One operational problem with an owner, a lifecycle and a recorded resolution.
 *
 * <p>Every resolution carries an actor, an action, a reason and a time
 * (architecture rule 8). An investigation, the "loaded but not received" case,
 * is never resolved, closed or cancelled by the system actor: it waits for a
 * person, and no record is amended to make the evidence agree (R-RCP-07).
 *
 * <p>Pure: the actor, the time and today's date are arguments.
 *
 * @param investigation a shortage investigation linking check, proof and receipt (R-RCP-07)
 * @param sourceKey the event that raised it, so a redelivered event raises nothing new
 * @param redeliveryRequestedAt set once; a redelivery is requested at most once per issue
 * @param escalatedAt when it waited past its severity's deadline unassigned
 */
public record Issue(
    UUID issueId,
    IssueType type,
    IssueSeverity severity,
    IssueStatus status,
    String depotCode,
    Optional<String> outletId,
    List<SubjectRef> subjects,
    String description,
    boolean investigation,
    Optional<String> sourceKey,
    Optional<UUID> assignee,
    Optional<Resolution> resolution,
    Optional<Instant> redeliveryRequestedAt,
    Optional<Instant> escalatedAt,
    UUID raisedBy,
    Instant raisedAt,
    long rowVersion) {

  /** The subject types the contract names. */
  public static final Set<String> SUBJECT_TYPES =
      Set.of("order", "trip", "delivery", "receipt", "shortfall", "vehicle");

  /** Problems a redelivery can answer: goods that did not reach the outlet intact. */
  public static final Set<IssueType> REDELIVERABLE =
      EnumSet.of(
          IssueType.FAILED_DELIVERY, IssueType.RECEIPT_DISPUTE, IssueType.DAMAGED_GOODS,
          IssueType.LATE_DELIVERY, IssueType.STOCK_DISCREPANCY, IssueType.OTHER);

  /** @param by the person who decided; never the system for an investigation */
  public record Resolution(ResolutionAction action, String note, UUID by, Instant at) {}

  public Issue {
    Objects.requireNonNull(issueId, "issueId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(severity, "severity");
    subjects = List.copyOf(subjects);
  }

  public static Issue raise(
      UUID issueId,
      IssueType type,
      IssueSeverity severity,
      String depotCode,
      Optional<String> outletId,
      List<SubjectRef> subjects,
      String description,
      boolean investigation,
      Optional<String> sourceKey,
      UUID raisedBy,
      Instant at) {
    if (depotCode == null || depotCode.isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "an issue needs a depot", List.of("R-ISS-03"));
    }
    String what = description == null ? "" : description.trim();
    if (what.length() < 3) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "describe the issue in at least three characters", List.of("R-ISS-03"));
    }
    return new Issue(
        issueId, type, severity, IssueStatus.OPEN, depotCode, outletId.filter(o -> !o.isBlank()),
        normalised(subjects), what, investigation, sourceKey, Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), raisedBy, at, 1);
  }

  public Issue assign(UUID assigneeUserId) {
    IssueLifecycle.require(status, IssueStatus.ASSIGNED);
    Objects.requireNonNull(assigneeUserId, "assignee");
    return with(IssueStatus.ASSIGNED, Optional.of(assigneeUserId), resolution, redeliveryRequestedAt);
  }

  public Issue resolve(ResolutionAction action, String note, UUID actor, Instant at) {
    IssueLifecycle.require(status, IssueStatus.RESOLVED);
    requireAPerson(actor, "resolve");
    String why = note == null ? "" : note.trim();
    if (why.length() < 3) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "a resolution needs a reason of at least three characters",
          List.of("R-ISS-02"));
    }
    return with(IssueStatus.RESOLVED, assignee, Optional.of(new Resolution(action, why, actor, at)), redeliveryRequestedAt);
  }

  /** A replacement was loaded for a loading shortfall. Resolves the issue as {@code REPLACEMENT}. */
  public Issue recordReplacement(UUID tripId, UUID orderId, String note, UUID actor, Instant at) {
    if (type != IssueType.LOADING_SHORTFALL) {
      throw new DomainException(
          ErrorCode.CONFLICT, "only a loading shortfall takes a replacement; this is " + type, List.of("R-ISS-04"));
    }
    requireSubject("trip", tripId);
    requireSubject("order", orderId);
    return resolve(ResolutionAction.REPLACEMENT, note, actor, at);
  }

  /**
   * Asks Ordering for a new order linked to the original (decision B17). Exactly
   * once per issue: a second request is a conflict, never a second order.
   */
  public Issue scheduleRedelivery(
      UUID orderId, LocalDate requestedDate, LocalDate today, String note, UUID actor, Instant at) {
    if (!REDELIVERABLE.contains(type)) {
      throw new DomainException(
          ErrorCode.CONFLICT, "a " + type + " issue is not answered by a redelivery", List.of("R-ISS-04"));
    }
    if (redeliveryRequestedAt.isPresent()) {
      throw new DomainException(
          ErrorCode.CONFLICT, "a redelivery was already requested for issue " + issueId, List.of("R-ISS-05"));
    }
    requireSubject("order", orderId);
    if (requestedDate.isBefore(today)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "a redelivery cannot be requested for a past date", List.of("R-ISS-05"));
    }
    Issue resolved = resolve(ResolutionAction.REDELIVERY, note, actor, at);
    return resolved.with(resolved.status, resolved.assignee, resolved.resolution, Optional.of(at));
  }

  public Issue close(UUID actor) {
    IssueLifecycle.require(status, IssueStatus.CLOSED);
    requireAPerson(actor, "close");
    return with(IssueStatus.CLOSED, assignee, resolution, redeliveryRequestedAt);
  }

  /** Raised in error. Keeps its history; never deleted. */
  public Issue cancel(String reason, UUID actor, Instant at) {
    IssueLifecycle.require(status, IssueStatus.CANCELLED);
    requireAPerson(actor, "cancel");
    String why = reason == null ? "" : reason.trim();
    if (why.length() < 3) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "a cancellation needs a reason of at least three characters",
          List.of("R-ISS-02"));
    }
    return with(IssueStatus.CANCELLED, assignee, resolution, redeliveryRequestedAt);
  }

  /** Still open and unassigned past its severity's deadline (an escalation timer). */
  public boolean isOverdue(Instant now, Duration deadline) {
    return status == IssueStatus.OPEN && escalatedAt.isEmpty() && !now.isBefore(raisedAt.plus(deadline));
  }

  public Issue escalate(Instant now, Duration deadline) {
    if (!isOverdue(now, deadline)) {
      throw new DomainException(ErrorCode.CONFLICT, "issue " + issueId + " is not overdue", List.of("R-ISS-06"));
    }
    return new Issue(
        issueId, type, severity, status, depotCode, outletId, subjects, description, investigation, sourceKey,
        assignee, resolution, redeliveryRequestedAt, Optional.of(now), raisedBy, raisedAt, rowVersion);
  }

  public boolean concerns(SubjectRef subject) {
    return subjects.contains(subject);
  }

  // ---- internals -----------------------------------------------------------

  /** R-RCP-07: an investigation is a person's decision, never the system's. */
  private void requireAPerson(UUID actor, String verb) {
    if (investigation && Actor.SYSTEM_ID.equals(actor)) {
      throw new DomainException(
          ErrorCode.CONSTRAINT_VIOLATED,
          "a shortage investigation is never auto-resolved; a person must " + verb + " it",
          List.of("R-RCP-07"));
    }
  }

  private void requireSubject(String subjectType, UUID id) {
    if (id == null || !concerns(new SubjectRef(subjectType, id.toString()))) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "issue " + issueId + " is not about " + subjectType + " " + id,
          List.of("R-ISS-04"));
    }
  }

  private Issue with(
      IssueStatus to, Optional<UUID> who, Optional<Resolution> how, Optional<Instant> redelivery) {
    return new Issue(
        issueId, type, severity, to, depotCode, outletId, subjects, description, investigation, sourceKey,
        who, how, redelivery, escalatedAt, raisedBy, raisedAt, rowVersion);
  }

  /** At least one subject, of a known type, with no duplicates (decision 11). */
  private static List<SubjectRef> normalised(List<SubjectRef> subjects) {
    if (subjects == null || subjects.isEmpty()) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "an issue must be about at least one order, trip, delivery, receipt, "
              + "shortfall or vehicle", List.of("R-ISS-03"));
    }
    Set<SubjectRef> unique = new LinkedHashSet<>();
    for (SubjectRef s : subjects) {
      if (s == null || s.type() == null || !SUBJECT_TYPES.contains(s.type()) || s.id() == null || s.id().isBlank()) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "a subject is one of " + SUBJECT_TYPES + " with an id", List.of("R-ISS-03"));
      }
      unique.add(s);
    }
    return List.copyOf(unique);
  }
}
