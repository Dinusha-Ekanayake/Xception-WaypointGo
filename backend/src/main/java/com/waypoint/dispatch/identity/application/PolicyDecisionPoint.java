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
import java.util.Set;
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
  private volatile Set<String> implementedActions = Set.of();

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
    List<Statement> statements = statementsInForce(actor);

    Map<String, String> context = new HashMap<>(extra);
    context.putIfAbsent("wpt:now", clock.now().toString());
    context.putIfAbsent("wpt:actorId", actor.userId().toString());

    return PolicyEvaluator.evaluate(statements, new AccessRequest(action, resource, context));
  }

  /**
   * One read of the generation on whatever connection this thread is using, then
   * the cache. Only a mismatch costs a reload, which runs as the identity module
   * in a transaction of its own because the caller may be inside another
   * module's command.
   */
  private List<Statement> statementsInForce(Actor actor) {
    long generation = policies.generationHere();
    return cache
        .statementsAt(actor.userId(), generation)
        .orElseGet(
            () ->
                database.readAs(
                    ModuleRole.IAM,
                    actor.userId(),
                    () -> {
                      // The generation first: a change that lands between the two
                      // reads leaves this entry older than its statements, so the
                      // next reader reloads. The other order would hide the change.
                      long loadedAt = policies.generation();
                      return cache.store(
                          actor.userId(), loadedAt, policies.statementsFor(actor.userId()));
                    }));
  }

  @Override
  public Optional<String> denyReason(Actor actor, String action, String resource, Command command) {
    // A command is routed only for an action the catalogue says is enforced. A
    // read is asked with no command, and is its controller's own declaration.
    if (command != null && !implemented(action)) {
      return Optional.of(deny(actor, action, resource, action + " is not implemented"));
    }
    Decision decision = decide(actor, action, resource, Map.of());
    if (decision.allowed()) {
      metrics.increment("waypoint.authorization.allowed", "action", action);
      return Optional.empty();
    }
    return Optional.of(deny(actor, action, resource, decision.reason()));
  }

  /**
   * SEC-03. The first answer was given before the transaction opened; this one
   * is given inside it, against the generation the command's own snapshot sees.
   * A permission revoked in between is refused here and the command changes
   * nothing. The bus audits the refusal after the rollback, since a row written
   * now would roll back with everything else.
   */
  @Override
  public Optional<String> denyReasonInTransaction(
      Actor actor, String action, String resource, Command command) {
    Decision decision = decide(actor, action, resource, Map.of());
    if (decision.allowed()) {
      return Optional.empty();
    }
    metrics.increment("waypoint.race.lost", "path", "command:recheck");
    return Optional.of(decision.reason());
  }

  private String deny(Actor actor, String action, String resource, String reason) {
    metrics.increment("waypoint.authorization.denied", "action", action);
    // Debug only: the audit row below is the record. An INFO line per denial would
    // let a scripted probe flood the log with resource ids.
    log.debug("Denied {} on {} for {}: {}", action, resource, actor.userId(), reason);
    // A denial that leaves no trace is how an access problem becomes invisible.
    audit.recordStandalone(
        AuditEntry.denied(actor.userId(), actor.deviceId(), action, resource, reason));
    return reason;
  }

  /**
   * The catalogue changes only with a migration, so a hit is remembered. A miss
   * is read again before it is believed, so an instance that started before the
   * migration ran does not refuse an action that has since been switched on.
   */
  private boolean implemented(String action) {
    if (implementedActions.contains(action)) {
      return true;
    }
    implementedActions = database.readAs(ModuleRole.IAM, null, policies::implementedActions);
    return implementedActions.contains(action);
  }
}
