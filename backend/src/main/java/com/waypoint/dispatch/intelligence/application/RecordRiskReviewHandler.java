package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.RiskReviewViews;
import com.waypoint.dispatch.intelligence.contract.RiskReviewViews.ReviewView;
import com.waypoint.dispatch.intelligence.domain.RiskReview;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcRiskReviews;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
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
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Roadmap T1-01: the dispatcher keeps or swaps a stop likely to be late, before
 * Publish, with a reason (rule 8). Only ever added, so no expectedVersion; the
 * command id makes a retry harmless. A plan outside the actor's scope is not
 * found, and a stop the estimate does not list is refused: a review is of a
 * stop at risk, as the screen showed it.
 */
@Component
class RecordRiskReviewHandler implements CommandHandler {
  private final RiskReviewQuery query;
  private final JdbcRiskReviews reviews;
  private final Database database;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  RecordRiskReviewHandler(RiskReviewQuery query, JdbcRiskReviews reviews, Database database, Metrics metrics, Clock clock) {
    this.query = query;
    this.reviews = reviews;
    this.database = database;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return RiskReviewViews.RECORD;
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
    return "wpt:ml:plan:" + CommandPayload.of(command).text("planId");
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload p = CommandPayload.of(command);
    UUID planId = p.uuid("planId");
    UUID orderId = p.uuid("orderId");
    RiskReview.Choice choice = RiskReview.choice(p.requiredText("choice"));
    String reason = RiskReview.reason(p.text("reason"));
    PlanView plan = query.plan(planId)
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No plan " + planId + " within your scope"));
    boolean inScope = Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_depot(?) AS ok", plan.depotCode()).get("ok"));
    if (!inScope) {
      throw new DomainException(ErrorCode.FORBIDDEN, "wpt:ml:plan:" + planId + " is outside the actor's scope");
    }
    RiskReview.Flagged stop = query.flagged(plan).stream().filter(f -> f.orderId().equals(orderId)).findFirst()
        .orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_FAILED, "That stop is not listed as at risk on this plan"));
    Instant now = clock.now();
    UUID id = UuidV7.generate(now, random);
    reviews.insert(new ReviewView(id, planId, orderId, choice.name(), reason, actor.userId(), now), plan.depotCode(), stop.percent());
    metrics.increment("waypoint.ml.risk_review", "choice", choice.name().toLowerCase(java.util.Locale.ROOT));
    return Map.of("reviewId", id.toString(), "planId", planId.toString(), "orderId", orderId.toString(), "choice", choice.name());
  }
}
