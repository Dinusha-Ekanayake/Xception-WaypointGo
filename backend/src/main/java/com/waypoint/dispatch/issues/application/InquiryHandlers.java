package com.waypoint.dispatch.issues.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.issues.contract.IssueCommands;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.issues.domain.Issue;
import com.waypoint.dispatch.issues.domain.WriteOffRules;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueAttachments;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository.Stored;
import com.waypoint.dispatch.issues.infrastructure.JdbcWriteOffs;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Operations' inquiry into an issue (roadmap FF-03, P-DG-1, P-DG-4). The
 * inquiry itself happens between people; the system records its outcome and,
 * when goods are written off, the value and its approval, each with an actor, a
 * reason and a time (rule 8). Only the depot's scope may act: an outlet or a
 * driver never records an inquiry.
 */
final class InquiryHandlers {
  private InquiryHandlers() {}

  /** The scope of an inquiry is the issue's depot, checked in SQL in this transaction (rule 7). */
  static void requireDepot(Database database, String depotCode) {
    Map<String, Object> row =
        database.queryOne("SELECT app.actor_is_system() OR app.actor_has_depot(?) AS ok", depotCode);
    if (row == null || !Boolean.TRUE.equals(row.get("ok"))) {
      throw new DomainException(
          ErrorCode.FORBIDDEN, "depot " + depotCode + " is outside the actor's scope", List.of("R-ISS-07"));
    }
  }

  static BigDecimal depotLimit(JdbcIssueRepository issues, Instant now) {
    String value =
        issues.parameters(now.atZone(Clock.OPERATING_ZONE).toLocalDate()).getOrDefault("write_off.depot_limit_lkr", "25000");
    return new BigDecimal(value);
  }

  @Component
  static class RecordInquiryHandler implements CommandHandler {
    private final Database database;
    private final JdbcIssueRepository issues;
    private final JdbcIssueAttachments attachments;
    private final JdbcWriteOffs writeOffs;
    private final OrderQuery orders;
    private final Metrics metrics;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    RecordInquiryHandler(
        Database database, JdbcIssueRepository issues, JdbcIssueAttachments attachments, JdbcWriteOffs writeOffs,
        OrderQuery orders, Metrics metrics, Clock clock) {
      this.database = database;
      this.issues = issues;
      this.attachments = attachments;
      this.writeOffs = writeOffs;
      this.orders = orders;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return IssueCommands.RECORD_INQUIRY;
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
      CommandPayload payload = CommandPayload.of(command);
      UUID issueId = payload.uuid("issueId");
      String outcome = WriteOffRules.requireOutcome(payload.text("outcome"));
      List<UUID> photos = WriteOffRules.requirePhotos(RaiseIssueHandler.attachmentIds(command));
      String valueText = payload.text("writeOffLkr");
      Optional<BigDecimal> value = Optional.empty();
      if (valueText != null && !valueText.isBlank()) {
        try {
          value = Optional.of(WriteOffRules.requireValue(new BigDecimal(valueText.trim())));
        } catch (NumberFormatException e) {
          throw new DomainException(ErrorCode.VALIDATION_FAILED, "writeOffLkr is a number of rupees");
        }
      }
      Instant now = clock.now();

      Issue issue =
          database.readAs(ModuleRole.ISSUES, Actor.SYSTEM_ID, () -> issues.find(issueId).map(Stored::issue))
              .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No issue " + issueId));
      requireDepot(database, issue.depotCode());

      if (!photos.isEmpty()) attachments.link(issueId, photos, now);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("issueId", issueId.toString());
      String reason = outcome;
      if (value.isPresent()) {
        boolean tech = techLine(issue);
        UUID writeOffId = UuidV7.generate(now, random);
        writeOffs.insert(
            writeOffId, issueId, issue.depotCode(), outcome, value.get(), tech, photos, actor.userId(), now,
            command.commandId());
        boolean head = WriteOffRules.needsHead(value.get(), tech, depotLimit(issues, now));
        result.put("writeOffId", writeOffId.toString());
        result.put("needsHead", head);
        reason = outcome + " · write-off LKR " + value.get().toPlainString() + (head ? " · needs the Head of Distribution" : "");
      }
      issues.record(
          issueId, Optional.of(issue.status()), issue.status(), "inquiry", reason, actor.userId(), Optional.empty(), now);
      metrics.increment("waypoint.issue.inquiry", "writeOff", value.isPresent() ? "yes" : "no");
      return result;
    }

    /** A Tech line is approved by the Head of Distribution whatever its value (P-DG-1). */
    private boolean techLine(Issue issue) {
      for (SubjectRef subject : issue.subjects()) {
        if (!"order".equals(subject.type())) continue;
        Optional<OrderView> order;
        try {
          order = orders.order(UUID.fromString(subject.id()));
        } catch (IllegalArgumentException e) {
          continue;
        }
        if (order.map(o -> "Tech".equalsIgnoreCase(o.brandCode())).orElse(false)) return true;
      }
      return false;
    }
  }

  @Component
  static class ApproveWriteOffHandler implements CommandHandler {
    private final Database database;
    private final JdbcIssueRepository issues;
    private final JdbcWriteOffs writeOffs;
    private final Metrics metrics;
    private final Clock clock;

    ApproveWriteOffHandler(
        Database database, JdbcIssueRepository issues, JdbcWriteOffs writeOffs, Metrics metrics, Clock clock) {
      this.database = database;
      this.issues = issues;
      this.writeOffs = writeOffs;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String kind() {
      return IssueCommands.APPROVE_WRITE_OFF;
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
      UUID id = CommandPayload.of(command).optionalUuid("writeOffId");
      return id == null ? null : "wpt:issue:write-off:" + id;
    }

    @Override
    public Object handle(Actor actor, Command command) {
      if (command.expectedVersion() == null) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "expectedVersion is required: send the write-off's row version");
      }
      CommandPayload payload = CommandPayload.of(command);
      UUID writeOffId = payload.uuid("writeOffId");
      JsonNode approveNode = command.payload() == null ? null : command.payload().get("approve");
      if (approveNode == null || !approveNode.isBoolean()) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "approve is true or false");
      }
      boolean approving = approveNode.asBoolean();
      String note = payload.text("note");
      if (note == null || note.trim().length() < 3) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "Say why, in at least three characters");
      }
      Instant now = clock.now();

      JdbcWriteOffs.Row row =
          database.readAs(ModuleRole.ISSUES, Actor.SYSTEM_ID, () -> writeOffs.find(writeOffId))
              .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No write-off " + writeOffId));
      requireDepot(database, row.depotCode());
      if (!"recorded".equals(row.status())) {
        throw new DomainException(ErrorCode.CONFLICT, "This write-off is already " + row.status());
      }
      Issue issue =
          issues.find(row.issueId()).map(Stored::issue)
              .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No issue " + row.issueId()));
      Map<String, Object> head = database.queryOne("SELECT app.actor_holds_role(ARRAY['ops_head']) AS head");
      WriteOffRules.requireMayDecide(
          actor.userId(), row.recordedBy(), issue.resolution().map(Issue.Resolution::by), approving, row.valueLkr(),
          row.techLine(), depotLimit(issues, now), head != null && Boolean.TRUE.equals(head.get("head")));

      String status = approving ? "approved" : "rejected";
      if (writeOffs.decide(writeOffId, command.expectedVersion(), status, actor.userId(), now, note.trim()) == 0) {
        throw new DomainException(
            ErrorCode.VERSION_CONFLICT, "The write-off changed since this screen loaded; reload and decide again");
      }
      issues.record(
          row.issueId(), Optional.of(issue.status()), issue.status(), "write_off_" + status,
          "LKR " + row.valueLkr().toPlainString() + ": " + note.trim(), actor.userId(), Optional.empty(), now);
      metrics.increment("waypoint.issue.write_off", "decision", status);
      return Map.of("writeOffId", writeOffId.toString(), "status", status, "rowVersion", command.expectedVersion() + 1);
    }
  }
}
