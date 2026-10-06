package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.issues.contract.IssueEvents.DecisionExpired;
import com.waypoint.dispatch.issues.contract.IssueViews.DecisionView;
import com.waypoint.dispatch.issues.domain.DecisionRules;
import com.waypoint.dispatch.issues.domain.DecisionRules.Kind;
import com.waypoint.dispatch.issues.infrastructure.JdbcDecisionRequests;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * "Waiting forever" is not a state (FF-09, R-ISS-13): a question with no answer
 * by its deadline is marked expired, once, and {@code decision.expired} tells the
 * dispatcher to phone the store. A store may still answer; the answer is kept
 * and marked late.
 */
@Component
public class DecisionExpiryJob implements ScheduledJob {
  private final Database database;
  private final JdbcDecisionRequests decisions;
  private final EventPublisher events;
  private final Metrics metrics;

  public DecisionExpiryJob(Database database, JdbcDecisionRequests decisions, EventPublisher events, Metrics metrics) {
    this.database = database;
    this.decisions = decisions;
    this.events = events;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "issues.decision-expiry";
  }

  @Override
  public String cron() {
    return "0 * * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ISSUES;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many questions expired */
  public int runAt(Instant now) {
    List<DecisionView> due = database.asSystem(ModuleRole.ISSUES, () -> decisions.due(now));
    int expired = 0;
    for (DecisionView d : due) {
      boolean done = database.asSystem(ModuleRole.ISSUES, () -> {
        if (decisions.expire(d.requestId(), now) == 0) {
          return false;
        }
        String question = DecisionRules.question(
            Kind.parse(d.kind()), d.receiveAt(), d.aboutOutletId(), d.aboutUnits(), Clock.OPERATING_ZONE);
        events.publish(
            Actor.SYSTEM,
            new DecisionExpired(
                d.requestId(), d.kind(), d.depotCode(), d.serviceDate(), d.deliveryId(), d.outletId(), d.deadline(),
                question));
        return true;
      });
      if (done) {
        expired++;
        metrics.increment("waypoint.issue.decision_expired", "kind", d.kind());
      }
    }
    return expired;
  }
}
