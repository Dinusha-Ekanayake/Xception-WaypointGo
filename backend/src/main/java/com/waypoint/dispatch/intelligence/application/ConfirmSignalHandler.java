package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.PlaybookViews;
import com.waypoint.dispatch.intelligence.domain.ReportTriage;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * R-ML-37: the dispatcher confirms what a message read unsure, or from a voice
 * note heard unclearly, reports: one of the report situations, or none. What they
 * confirm is what counts on the trip's problem room, and is the label Jev's
 * reading is scored against. Rule 8: who and when are kept; the latest stands.
 * The row is read as the dispatcher, so a trip outside their depots is refused.
 */
@Component
class ConfirmSignalHandler implements CommandHandler {
  private final JdbcMessageSituations store;
  private final Metrics metrics;
  private final Clock clock;

  ConfirmSignalHandler(JdbcMessageSituations store, Metrics metrics, Clock clock) {
    this.store = store;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlaybookViews.CONFIRM_SIGNAL;
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
    String situation = p.requiredText("situation").strip().toUpperCase(Locale.ROOT);
    if (!ReportTriage.OPTIONS.containsKey(situation)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "situation is one of " + ReportTriage.OPTIONS.keySet());
    }
    Map<String, Object> row = store.row(messageId)
        .filter(r -> tripId.equals(r.get("trip_id")))
        .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:trip:" + tripId + " is outside the actor's scope"));
    if (!store.confirm(messageId, tripId, situation, actor.userId(), clock.now())) {
      throw new DomainException(ErrorCode.CONFLICT, "This message has not been read yet; read it yourself.");
    }
    metrics.increment("waypoint.ml.signal_confirmed", "situation", situation.toLowerCase(Locale.ROOT));
    return Map.of("messageId", messageId.toString(), "situation", situation, "depotCode", row.get("depot_code"));
  }
}
