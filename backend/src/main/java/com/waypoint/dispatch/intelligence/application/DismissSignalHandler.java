package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.PlaybookViews;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcMessageSituations;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * R-ML-35: a message read automatically was not the problem it seemed, or is
 * handled, so the dispatcher takes it off the trip's Next step card. Rule 8: who,
 * why and when are kept. The row is read as the dispatcher, so a trip outside
 * their depots is not found, and refused; taking it off twice is harmless.
 */
@Component
class DismissSignalHandler implements CommandHandler {
  static final int REASON_MAX = 300;

  private final JdbcMessageSituations store;
  private final Metrics metrics;
  private final Clock clock;

  DismissSignalHandler(JdbcMessageSituations store, Metrics metrics, Clock clock) {
    this.store = store;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlaybookViews.DISMISS_SIGNAL;
  }

  @Override
  public String action() {
    return kind();
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public String resource(Command command) {
    return "wpt:ml:trip:" + CommandPayload.of(command).text("tripId");
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload p = CommandPayload.of(command);
    String tripId = p.requiredText("tripId");
    UUID messageId = p.uuid("messageId");
    String reason = p.requiredText("reason").strip();
    if (reason.isEmpty() || reason.length() > REASON_MAX) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Say why in 1 to " + REASON_MAX + " characters.");
    }
    Map<String, Object> row = store.row(messageId)
        .filter(r -> tripId.equals(r.get("trip_id")))
        .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:trip:" + tripId + " is outside the actor's scope"));
    if (row.get("dismissed_at") != null) {
      return Map.of("messageId", messageId.toString(), "alreadyDismissed", true);
    }
    store.dismiss(messageId, tripId, actor.userId(), reason, clock.now());
    metrics.increment("waypoint.ml.signal_dismissed");
    return Map.of("messageId", messageId.toString(), "alreadyDismissed", false);
  }
}
