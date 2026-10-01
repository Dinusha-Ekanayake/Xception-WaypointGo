package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.issues.contract.IssueCommands;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueResolved;
import com.waypoint.dispatch.issues.contract.IssueEvents.RedeliveryRequested;
import com.waypoint.dispatch.issues.contract.IssueEvents.ShortfallResolved;
import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.domain.ResolutionAction;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The six decisions on an existing issue. Each records an actor, a reason and a time (rule 8). */
final class IssueHandlers {
  private IssueHandlers() {}

  private static IssueResolved resolved(Issue after) {
    Issue.Resolution r = after.resolution().orElseThrow();
    return new IssueResolved(after.issueId(), after.depotCode(), after.outletId(), r.action().name(), r.at());
  }

  @Component
  static class AssignIssueHandler extends IssueCommandHandler {
    AssignIssueHandler(Database d, JdbcIssueRepository i, EventPublisher e, Metrics m, Clock c) {
      super(d, i, e, m, c);
    }

    @Override
    public String kind() {
      return IssueCommands.ASSIGN;
    }

    @Override
    protected Issue decide(Issue current, Command command, Actor actor, Instant now) {
      return current.assign(CommandPayload.of(command).uuid("assigneeUserId"));
    }

    @Override
    protected String verb() {
      return "assigned";
    }

    @Override
    protected String reason(Issue after, Command command) {
      return "assigned to " + after.assignee().orElseThrow();
    }
  }

  @Component
  static class ResolveIssueHandler extends IssueCommandHandler {
    ResolveIssueHandler(Database d, JdbcIssueRepository i, EventPublisher e, Metrics m, Clock c) {
      super(d, i, e, m, c);
    }

    @Override
    public String kind() {
      return IssueCommands.RESOLVE;
    }

    @Override
    protected Issue decide(Issue current, Command command, Actor actor, Instant now) {
      return current.resolve(
          ResolutionAction.parse(note(command, "action")), note(command, "note"), actor.userId(), now);
    }

    @Override
    protected String verb() {
      return "resolved";
    }

    @Override
    protected String reason(Issue after, Command command) {
      return after.resolution().orElseThrow().action().name() + ": " + after.resolution().orElseThrow().note();
    }

    @Override
    protected List<DomainEvent> announce(Issue before, Issue after, Command command, Instant now) {
      return List.of(resolved(after));
    }
  }

  /** A replacement for a loading shortfall was loaded; Loading may recheck and release (LOD-01). */
  @Component
  static class RecordReplacementHandler extends IssueCommandHandler {
    RecordReplacementHandler(Database d, JdbcIssueRepository i, EventPublisher e, Metrics m, Clock c) {
      super(d, i, e, m, c);
    }

    @Override
    public String kind() {
      return IssueCommands.RECORD_REPLACEMENT;
    }

    @Override
    protected Issue decide(Issue current, Command command, Actor actor, Instant now) {
      CommandPayload p = CommandPayload.of(command);
      return current.recordReplacement(p.uuid("tripId"), p.uuid("orderId"), note(command, "note"), actor.userId(), now);
    }

    @Override
    protected String verb() {
      return "resolved";
    }

    @Override
    protected String reason(Issue after, Command command) {
      return "replacement loaded: " + after.resolution().orElseThrow().note();
    }

    @Override
    protected List<DomainEvent> announce(Issue before, Issue after, Command command, Instant now) {
      CommandPayload p = CommandPayload.of(command);
      return List.of(
          new ShortfallResolved(
              after.issueId(), p.uuid("tripId"), p.uuid("orderId"), after.resolution().orElseThrow().note()),
          resolved(after));
    }
  }

  /**
   * Asks Ordering for a new order linked to the original (decision B17). Exactly
   * one {@code redelivery.requested} per issue: the domain refuses a second
   * request, and Ordering's unique source issue would ignore one anyway.
   */
  @Component
  static class ScheduleRedeliveryHandler extends IssueCommandHandler {
    ScheduleRedeliveryHandler(Database d, JdbcIssueRepository i, EventPublisher e, Metrics m, Clock c) {
      super(d, i, e, m, c);
    }

    @Override
    public String kind() {
      return IssueCommands.SCHEDULE_REDELIVERY;
    }

    @Override
    protected Issue decide(Issue current, Command command, Actor actor, Instant now) {
      CommandPayload p = CommandPayload.of(command);
      LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
      return current.scheduleRedelivery(
          p.uuid("orderId"), p.date("requestedDate"), today, note(command, "note"), actor.userId(), now);
    }

    @Override
    protected String verb() {
      return "resolved";
    }

    @Override
    protected String reason(Issue after, Command command) {
      return "redelivery requested: " + after.resolution().orElseThrow().note();
    }

    @Override
    protected List<DomainEvent> announce(Issue before, Issue after, Command command, Instant now) {
      CommandPayload p = CommandPayload.of(command);
      UUID orderId = p.uuid("orderId");
      return List.of(
          new RedeliveryRequested(
              after.issueId(), orderId, p.date("requestedDate"), after.resolution().orElseThrow().note()),
          resolved(after));
    }
  }

  @Component
  static class CloseIssueHandler extends IssueCommandHandler {
    CloseIssueHandler(Database d, JdbcIssueRepository i, EventPublisher e, Metrics m, Clock c) {
      super(d, i, e, m, c);
    }

    @Override
    public String kind() {
      return IssueCommands.CLOSE;
    }

    @Override
    protected Issue decide(Issue current, Command command, Actor actor, Instant now) {
      return current.close(actor.userId());
    }

    @Override
    protected String verb() {
      return "closed";
    }

    @Override
    protected String reason(Issue after, Command command) {
      return "resolution checked and closed";
    }
  }

  @Component
  static class CancelIssueHandler extends IssueCommandHandler {
    CancelIssueHandler(Database d, JdbcIssueRepository i, EventPublisher e, Metrics m, Clock c) {
      super(d, i, e, m, c);
    }

    @Override
    public String kind() {
      return IssueCommands.CANCEL;
    }

    @Override
    protected Issue decide(Issue current, Command command, Actor actor, Instant now) {
      return current.cancel(note(command, "reason"), actor.userId(), now);
    }

    @Override
    protected String verb() {
      return "cancelled";
    }

    @Override
    protected String reason(Issue after, Command command) {
      return "raised in error: " + note(command, "reason");
    }
  }
}
