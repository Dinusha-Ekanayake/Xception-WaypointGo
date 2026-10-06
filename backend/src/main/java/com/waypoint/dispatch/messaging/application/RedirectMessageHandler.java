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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code message:Redirect} (FF-04, R-MSG-08): the dispatcher moves a post off the
 * trip's channel, say a store naming another store's order. Messages are append
 * only, so the post is never changed: a redirect row of its own, with who, when
 * and why, hides it from the stores (row-level security). Redirecting again
 * changes nothing. Only a dispatcher who oversees the trip's depot may.
 */
@Component
public class RedirectMessageHandler implements CommandHandler {
  private final JdbcThreadRepository threads;
  private final Metrics metrics;
  private final Clock clock;

  RedirectMessageHandler(JdbcThreadRepository threads, Metrics metrics, Clock clock) {
    this.threads = threads;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return MessagingCommands.REDIRECT;
  }

  @Override
  public String action() {
    return MessagingCommands.REDIRECT;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.MESSAGING;
  }

  @Override
  public String resource(Command command) {
    String message = CommandPayload.of(command).text("messageId");
    return message == null ? null : "wpt:message:message:" + message;
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    UUID messageId = payload.uuid("messageId");
    String reason = MessagePolicy.redirectReason(payload.text("reason"));

    Map<String, Object> message = threads.messageForRedirect(messageId).orElseThrow(() -> outside(messageId));
    if (!threads.oversees((String) message.get("depot_code"))) {
      throw outside(messageId);
    }
    if (!"channel".equals(message.get("audience"))) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "message " + messageId + " was not posted to the trip's channel",
          List.of("R-MSG-08"));
    }
    boolean already = threads.redirect(messageId, actor.userId(), reason, clock.now(), command.commandId()) == 0;
    if (!already) {
      metrics.increment("waypoint.message.redirected");
    }
    return Map.of(
        "messageId", messageId.toString(),
        "threadId", message.get("thread_id").toString(),
        "alreadyRedirected", already);
  }

  private static DomainException outside(UUID messageId) {
    return new DomainException(
        ErrorCode.FORBIDDEN, "message " + messageId + " is not one you may redirect", List.of("R-MSG-08"));
  }
}
