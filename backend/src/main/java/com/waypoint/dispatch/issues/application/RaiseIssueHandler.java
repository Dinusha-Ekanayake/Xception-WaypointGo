package com.waypoint.dispatch.issues.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.issues.contract.IssueCommands;
import com.waypoint.dispatch.issues.contract.IssueEvents.IssueRaised;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueSeverity;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueStatus;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueType;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueAttachments;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Anyone may report a problem at any stage (R-EXE-09), within two limits that
 * are both data:
 *
 * <ul>
 *   <li><b>Which types</b> a role may raise is policy: the resource is
 *       {@code wpt:issue:type:<TYPE>}, and the role policies name the types each
 *       role may raise (issue #13, decision 2). A store raising a vehicle fault is
 *       refused by the bus before this runs.
 *   <li><b>Where</b> is scope: the actor must hold the issue's depot, or the
 *       outlet it names, or drive a vehicle of that depot today; a named outlet
 *       must belong to the depot.
 * </ul>
 */
@org.springframework.stereotype.Component
public class RaiseIssueHandler implements CommandHandler {
  private final JdbcIssueRepository issues;
  private final ReferenceQuery reference;
  private final EventPublisher events;
  private final Metrics metrics;
  private final Clock clock;
  private final IssueScope scope;
  private final JdbcIssueAttachments attachments;
  private final SecureRandom random = new SecureRandom();

  RaiseIssueHandler(
      JdbcIssueRepository issues,
      ReferenceQuery reference,
      EventPublisher events,
      Metrics metrics,
      Clock clock,
      IssueScope scope,
      JdbcIssueAttachments attachments) {
    this.attachments = attachments;
    this.issues = issues;
    this.reference = reference;
    this.events = events;
    this.metrics = metrics;
    this.clock = clock;
    this.scope = scope;
  }

  @Override
  public String kind() {
    return IssueCommands.RAISE;
  }

  @Override
  public String action() {
    return IssueCommands.RAISE;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ISSUES;
  }

  /** The type is the resource, so the role policies decide who may raise what. */
  @Override
  public String resource(Command command) {
    String type = CommandPayload.of(command).text("type");
    return type == null ? null : "wpt:issue:type:" + type.toUpperCase(Locale.ROOT);
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    IssueType type = parse(IssueType.class, payload.requiredText("type"), "type");
    IssueSeverity severity = parse(IssueSeverity.class, payload.requiredText("severity"), "severity");
    String depot = payload.requiredText("depotCode");
    Optional<String> outlet = Optional.ofNullable(payload.text("outletId"));
    Instant now = clock.now();

    outlet.ifPresent(
        o -> {
          String home =
              reference.outlet(o, null)
                  .orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_FAILED, "no outlet " + o))
                  .depotCode();
          if (!home.equals(depot)) {
            throw new DomainException(
                ErrorCode.VALIDATION_FAILED, "outlet " + o + " belongs to depot " + home + ", not " + depot,
                List.of("R-ISS-03"));
          }
        });
    scope.require(actor, depot, outlet);

    Issue issue =
        Issue.raise(
            UuidV7.generate(now, random), type, severity, depot, outlet, subjects(command),
            payload.requiredText("description"), false, Optional.empty(), actor.userId(), now);
    issues.insert(issue, now);
    // Photos named by id: those already here show at once, one still on the phone when it arrives.
    attachments.link(issue.issueId(), attachmentIds(command), now);
    issues.record(
        issue.issueId(), Optional.empty(), IssueStatus.OPEN, "raised", "raised by a person", actor.userId(),
        Optional.empty(), now);
    events.publish(
        actor, new IssueRaised(issue.issueId(), type, severity, depot, outlet, issue.subjects()));
    metrics.increment("waypoint.issue.raised", "type", type.name(), "severity", severity.name(), "by", "person");

    return Map.of("issueId", issue.issueId().toString(), "status", issue.status().name(), "rowVersion", 1L);
  }

  /** {@code attachmentIds: [uuid]}, optional: photos uploaded for the problem (issue:AttachPhoto). */
  static List<UUID> attachmentIds(Command command) {
    JsonNode node = command.payload() == null ? null : command.payload().get("attachmentIds");
    if (node == null || node.isNull()) {
      return List.of();
    }
    if (!node.isArray() || node.size() > 10) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "attachmentIds is a list of at most ten photo ids");
    }
    List<UUID> out = new ArrayList<>();
    for (JsonNode id : node) {
      try {
        out.add(UUID.fromString(id.asText()));
      } catch (IllegalArgumentException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "attachmentIds holds photo ids");
      }
    }
    return out;
  }

  /** {@code subjects: [{type, id}]}, at least one; the domain checks the types. */
  static List<SubjectRef> subjects(Command command) {
    JsonNode node = command.payload() == null ? null : command.payload().get("subjects");
    if (node == null || !node.isArray()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "subjects is required", List.of("R-ISS-03"));
    }
    List<SubjectRef> out = new ArrayList<>();
    for (JsonNode s : node) {
      JsonNode t = s.get("type");
      JsonNode id = s.get("id");
      if (t == null || !t.isTextual() || id == null || !id.isTextual()) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "each subject needs a type and an id", List.of("R-ISS-03"));
      }
      out.add(new SubjectRef(t.asText().trim().toLowerCase(Locale.ROOT), id.asText().trim()));
    }
    return out;
  }

  static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, field + " must be one of " + Arrays.toString(type.getEnumConstants()));
    }
  }
}
