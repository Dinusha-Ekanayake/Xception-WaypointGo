package com.waypoint.dispatch.intelligence.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews;
import com.waypoint.dispatch.intelligence.contract.PlaybookViews.SuggestionsView;
import com.waypoint.dispatch.intelligence.domain.Playbooks;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcDecisionSuggestions;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The suggestions shown beside a stop and which one the dispatcher took, or
 * none (PLAN, "How it works", 5). The log the learned chances will count, and
 * the measure of whether suggestions help. Only ever added; the command id makes
 * a retry harmless. A stop outside the dispatcher's depots is refused.
 */
@Component
class RecordSuggestionHandler implements CommandHandler {
  private final PlaybookQuery playbooks;
  private final JdbcDecisionSuggestions log;
  private final com.waypoint.dispatch.referencedata.contract.ReferenceQuery reference;
  private final com.waypoint.dispatch.execution.contract.ExecutionQuery execution;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  RecordSuggestionHandler(
      PlaybookQuery playbooks, JdbcDecisionSuggestions log,
      com.waypoint.dispatch.referencedata.contract.ReferenceQuery reference,
      com.waypoint.dispatch.execution.contract.ExecutionQuery execution, Metrics metrics, Clock clock) {
    this.playbooks = playbooks;
    this.log = log;
    this.reference = reference;
    this.execution = execution;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return PlaybookViews.RECORD;
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
    return "wpt:ml:delivery:" + CommandPayload.of(command).text("deliveryId");
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload p = CommandPayload.of(command);
    UUID deliveryId = p.uuid("deliveryId");
    String situation = Playbooks.Situation.parse(p.requiredText("situation")).name();
    // The scope check is the read's: a stop outside the dispatcher's depots is refused there.
    SuggestionsView now = playbooks.forStop(actor, deliveryId, situation);
    List<String> shown = new ArrayList<>();
    JsonNode list = command.payload() == null ? null : command.payload().get("shown");
    if (list == null || !list.isArray() || list.isEmpty() || list.size() > Playbooks.SHOWN) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "shown lists the 1 to " + Playbooks.SHOWN + " suggestions on screen");
    }
    list.forEach(n -> shown.add(Playbooks.Action.parse(n.asText()).name()));
    String chosenText = p.text("chosen");
    String chosen = chosenText == null ? null : Playbooks.Action.parse(chosenText).name();
    if (chosen != null && !shown.contains(chosen)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "chosen must be one of the suggestions shown");
    }
    String depot = execution.deliveryRecord(deliveryId)
        .flatMap(stop -> reference.outlet(stop.outletId(), null))
        .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:delivery:" + deliveryId + " is outside the actor's scope"))
        .depotCode();
    Instant at = clock.now();
    UUID id = UuidV7.generate(at, random);
    if (p.flag("shownOnly", false)) {
      // The chips are on screen; a decision taken later by any route is logged against them.
      if (chosen != null) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "a shown-only record names no choice");
      }
      log.impression(id, deliveryId, depot, now.situation(), now.version(), shown, actor.userId(), at);
      metrics.increment("waypoint.ml.suggestion_shown", "situation", now.situation().toLowerCase(java.util.Locale.ROOT));
      return Map.of("impressionId", id.toString());
    }
    log.insert(id, deliveryId, depot, now.situation(), now.version(), shown, chosen, actor.userId(), at);
    metrics.increment("waypoint.ml.suggestion", "situation", now.situation().toLowerCase(java.util.Locale.ROOT),
        "chosen", chosen == null ? "none" : chosen.toLowerCase(java.util.Locale.ROOT),
        "rank", chosen == null ? "none" : String.valueOf(shown.indexOf(chosen) + 1));
    return Map.of("suggestionId", id.toString(), "chosen", chosen == null ? "" : chosen);
  }
}
