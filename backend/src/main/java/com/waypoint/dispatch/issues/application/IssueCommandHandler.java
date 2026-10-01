package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository.Stored;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A decision on an existing issue: the same steps for assigning, resolving,
 * recording a replacement, scheduling a redelivery, closing and cancelling.
 *
 * <ol>
 *   <li>Scope: the issue's depot, or its outlet, or {@code 403} plus the bus's
 *       audit row. The issue's place is read as the system in a read-only
 *       transaction of its own, so out of scope is told apart from absent.
 *   <li>The version the caller saw, or {@code 409}: never last-writer-wins.
 *   <li>The domain decides; the issue, its history row and its events commit
 *       together (rule 8).
 * </ol>
 */
abstract class IssueCommandHandler implements CommandHandler {
  protected final Database database;
  protected final JdbcIssueRepository issues;
  protected final EventPublisher events;
  protected final Metrics metrics;
  protected final Clock clock;

  IssueCommandHandler(
      Database database, JdbcIssueRepository issues, EventPublisher events, Metrics metrics, Clock clock) {
    this.database = database;
    this.issues = issues;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** The decision on the issue as it stands. */
  protected abstract Issue decide(Issue current, Command command, Actor actor, Instant now);

  /** The history entry's action word: assigned, resolved, closed, cancelled. */
  protected abstract String verb();

  /** The history entry's reason, never personal data. */
  protected abstract String reason(Issue after, Command command);

  /** What other modules hear; empty by default. */
  protected List<DomainEvent> announce(Issue before, Issue after, Command command, Instant now) {
    return List.of();
  }

  @Override
  public String action() {
    return kind();
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ISSUES;
  }

  @Override
  public String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("issueId");
    return id == null ? null : "wpt:issue:issue:" + id;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    if (command.expectedVersion() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required to change an issue");
    }
    long expected = command.expectedVersion();
    UUID issueId = CommandPayload.of(command).uuid("issueId");
    Instant now = clock.now();

    requireScope(issueId);
    Issue current =
        issues.find(issueId).map(Stored::issue)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No issue " + issueId));
    if (current.rowVersion() != expected) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT,
          "issue " + issueId + " is at version " + current.rowVersion() + ", not " + expected);
    }

    Issue next = decide(current, command, actor, now);
    long version = issues.update(next, expected, now);
    issues.record(
        issueId, Optional.of(current.status()), next.status(), verb(), reason(next, command), actor.userId(),
        Optional.empty(), now);
    announce(current, next, command, now).forEach(e -> events.publish(actor, e));
    metrics.increment("waypoint.issue." + verb(), "type", next.type().name(), "severity", next.severity().name());

    return Map.of(
        "issueId", issueId.toString(),
        "status", next.status().name(),
        "rowVersion", version);
  }

  /** The issue's depot or outlet, read as the system; then the actor's scope, in this transaction. */
  private void requireScope(UUID issueId) {
    Issue issue =
        database.readAs(ModuleRole.ISSUES, Actor.SYSTEM_ID, () -> issues.find(issueId).map(Stored::issue))
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No issue " + issueId));
    IssueScope.require(database, issue.depotCode(), issue.outletId());
  }

  /** A reason of at least three characters, which every decision on an issue carries (rule 8). */
  static String note(Command command, String field) {
    return CommandPayload.of(command).requiredText(field);
  }
}
