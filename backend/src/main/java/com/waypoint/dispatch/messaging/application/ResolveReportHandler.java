package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.contract.MessagingCommands;
import com.waypoint.dispatch.messaging.domain.MessagePolicy;
import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code message:Resolve} (issue #136, R-MSG-07): the dispatcher marks a report
 * resolved, with a note. Its warning sign leaves the timeline; the report stays
 * on the thread with who resolved it, when and why (rule 8). Resolving it again
 * changes nothing. Only a dispatcher who oversees the trip's depot may: the
 * policy grants the action, row-level security decides the rows, and a report
 * the reader cannot see is a 403 plus an audit row.
 */
@Component
public class ResolveReportHandler implements CommandHandler {
  private final JdbcThreadRepository threads;
  private final Metrics metrics;
  private final Clock clock;

  ResolveReportHandler(JdbcThreadRepository threads, Metrics metrics, Clock clock) {
    this.threads = threads;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return MessagingCommands.RESOLVE;
  }

  @Override
  public String action() {
    return MessagingCommands.RESOLVE;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.MESSAGING;
  }

  @Override
  public String resource(Command command) {
    String message = CommandPayload.of(command).text("messageId");
    return message == null ? null : "wpt:message:report:" + message;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    UUID messageId = payload.uuid("messageId");
    Optional<String> note = MessagePolicy.resolutionNote(payload.text("note"));

    JdbcThreadRepository.Message report =
        threads.message(messageId).orElseThrow(() -> outside(messageId));
    if (!"report".equals(report.kind())) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "message " + messageId + " is not a report", List.of("R-MSG-07"));
    }
    if (report.resolvedAt().isPresent()) {
      return result(report, true);
    }
    Instant now = clock.now();
    if (!threads.resolve(messageId, actor.userId(), note, now)) {
      // Visible but not the reader's to resolve: a loader's own report, say.
      throw outside(messageId);
    }
    metrics.increment("waypoint.message.report_resolved", "by", "dispatcher");
    return result(report, false);
  }

  private static Map<String, Object> result(JdbcThreadRepository.Message report, boolean already) {
    return Map.of(
        "messageId", report.messageId().toString(),
        "threadId", report.threadId().toString(),
        "alreadyResolved", already);
  }

  private static DomainException outside(UUID messageId) {
    return new DomainException(
        ErrorCode.FORBIDDEN, "report " + messageId + " is not one you may resolve", List.of("R-MSG-07"));
  }
}
