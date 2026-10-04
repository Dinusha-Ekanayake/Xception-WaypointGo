package com.waypoint.dispatch.identity.domain.policy;

import java.util.List;

/**
 * Decides one access question against a set of statements.
 *
 * <p>The order is the whole contract and does not vary:
 *
 * <ol>
 *   <li>Start from Deny. Nothing is permitted by default.
 *   <li>Any matching Deny wins immediately, whatever else allows it. An explicit
 *       Deny is how a broad grant is fenced without rewriting it.
 *   <li>Otherwise any matching Allow grants.
 *   <li>Otherwise Deny, because no policy spoke to this.
 * </ol>
 *
 * <p>Pure and framework-free: this is the most security-sensitive code in the
 * system, so it is the cheapest to test exhaustively.
 */
public final class PolicyEvaluator {

  private PolicyEvaluator() {}

  public static Decision evaluate(List<Statement> statements, AccessRequest request) {
    Statement allowed = null;

    for (Statement statement : statements) {
      if (!statement.matches(request.action(), request.resource(), request.context())) {
        continue;
      }
      if (statement.effect() == Effect.DENY) {
        return Decision.explicitDeny(statement.sid());
      }
      if (allowed == null) {
        allowed = statement;
      }
    }

    return allowed == null
        ? Decision.defaultDeny(request.action())
        : Decision.allow(allowed.sid());
  }
}
