package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.CapacityViews;
import com.waypoint.dispatch.intelligence.contract.CapacityViews.CapacityAction;
import com.waypoint.dispatch.intelligence.contract.CapacityViews.CapacityDecisionView;
import com.waypoint.dispatch.intelligence.domain.CapacityDecisionRule;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcCapacityRepository;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The dispatcher recording what they decided about a week the capacity plan
 * shows short (roadmap T2-01): hire, move workshop days, advise stores, accept
 * the shortfall. A decision carries who, when and why (rule 8) and is only ever
 * added, so it needs no expectedVersion; the command id makes a retry harmless.
 * A depot outside the actor's scope is refused before anything is written.
 */
@Component
class RecordCapacityDecisionHandler implements CommandHandler {
  private final Database database;
  private final JdbcCapacityRepository repository;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  RecordCapacityDecisionHandler(Database database, JdbcCapacityRepository repository, Metrics metrics, Clock clock) {
    this.database = database;
    this.repository = repository;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return CapacityViews.RECORD;
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
    return "wpt:ml:capacity:" + CommandPayload.of(command).text("depotCode");
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload p = CommandPayload.of(command);
    String depot = p.requiredText("depotCode");
    Integer year = p.optionalInt("isoYear");
    Integer week = p.optionalInt("isoWeek");
    if (year == null || week == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "isoYear and isoWeek are required");
    }
    CapacityAction action = CapacityDecisionRule.action(p.requiredText("action"));
    Integer vehicles = p.optionalInt("vehicles");
    String reason = p.requiredText("reason").trim();
    CapacityDecisionRule.check(year, week, action, vehicles, reason);
    boolean inScope = Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", depot).get("ok"));
    if (!inScope) {
      throw new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:capacity:" + depot + " is outside the actor's scope");
    }
    Instant now = clock.now();
    UUID id = UuidV7.generate(now, random);
    repository.insert(new CapacityDecisionView(
        id, depot, year, week, action, Optional.ofNullable(vehicles), p.flag("refrigerated", false), reason, actor.userId(), now));
    metrics.increment("waypoint.ml.capacity_decision", "action", action.name().toLowerCase(java.util.Locale.ROOT));
    return Map.of("decisionId", id.toString(), "depotCode", depot, "isoYear", year, "isoWeek", week, "action", action.name());
  }
}
