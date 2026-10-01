package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.domain.policy.AccessRequest;
import com.waypoint.dispatch.identity.domain.policy.Decision;
import com.waypoint.dispatch.identity.domain.policy.PolicyEvaluator;
import com.waypoint.dispatch.identity.domain.policy.Statement;
import com.waypoint.dispatch.identity.infrastructure.JdbcPolicyRepository;
import com.waypoint.dispatch.identity.infrastructure.PolicyCache;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandAuthorizer;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single place an access decision is made.
 *
 * <p>Wiring this bean is also what unblocks the command bus, which denies
 * everything until an authorizer exists. That is deliberate: a system with no
 * authorization must refuse work rather than perform it unchecked.
 *
 * <p>Denials are audited with the statement that caused them, so "why can this
 * person not do that" is answerable from the audit log rather than by reading
 * policy documents and guessing.
 */
@Component
public class PolicyDecisionPoint implements CommandAuthorizer {
  private static final Logger log = LoggerFactory.getLogger(PolicyDecisionPoint.class);

  private final JdbcPolicyRepository policies;
  private final PolicyCache cache;
  private final Database database;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;

  public PolicyDecisionPoint(
      JdbcPolicyRepository policies,
      PolicyCache cache,
      Database database,
      AuditLog audit,
      Metrics metrics,
      Clock clock) {
    this.policies = policies;
    this.cache = cache;
    this.database = database;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** Answers the question, without recording anything. */
  public Decision decide(Actor actor, String action, String resource, Map<String, String> extra) {
    List<Statement> statements =
        cache.statementsFor(
            actor.userId(),
            () ->
                database.asModule(
                    ModuleRole.IAM, actor.userId(), () -> policies.statementsFor(actor.userId())));

    Map<String, String> context = new HashMap<>(extra);
    context.putIfAbsent("wpt:now", clock.now().toString());
    context.putIfAbsent("wpt:actorId", actor.userId().toString());

    return PolicyEvaluator.evaluate(statements, new AccessRequest(action, resource, context));
  }

  @Override
  public Optional<String> denyReason(Actor actor, String action, String resource, Command command) {
    Decision decision = decide(actor, action, resource, Map.of());
    if (decision.allowed()) {
      metrics.increment("waypoint.authorization.allowed", "action", action);
      return Optional.empty();
    }
    metrics.increment("waypoint.authorization.denied", "action", action);
    // Debug only: the audit row below is the record. An INFO line per denial would
    // let a scripted probe flood the log with resource ids.
    log.debug(
        "Denied {} on {} for {}: {}", action, resource, actor.userId(), decision.reason());
    // A denial that leaves no trace is how an access problem becomes invisible.
    audit.recordStandalone(
        AuditEntry.denied(
            actor.userId(), actor.deviceId(), action, resource, decision.reason()));
    return Optional.of(decision.reason());
  }
}
