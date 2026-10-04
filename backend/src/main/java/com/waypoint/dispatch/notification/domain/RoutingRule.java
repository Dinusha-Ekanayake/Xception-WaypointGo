package com.waypoint.dispatch.notification.domain;

import java.util.Optional;
import java.util.Set;

/**
 * One row of the routing matrix: who hears about an event, where, how and in
 * what words.
 *
 * @param role the recipient role code, for example {@code store_manager}
 * @param scope where the role must hold scope for the event's target
 * @param push whether this goes out by web push as well as to the inbox
 * @param whenFact with {@code whenValues}, a condition on one of the event's facts
 * @param onlyIfNoneFor applies only when the rule for that role reached nobody
 *     (LOD-05: a released trip with no driver goes to the dispatcher)
 */
public record RoutingRule(
    String eventType,
    String role,
    ScopeKind scope,
    boolean push,
    Optional<String> whenFact,
    Set<String> whenValues,
    Optional<String> onlyIfNoneFor,
    String titleTemplate,
    String bodyTemplate) {

  public RoutingRule {
    whenValues = Set.copyOf(whenValues);
  }

  /** Unconditional, with no fallback: most rows. */
  public static RoutingRule of(
      String eventType, String role, ScopeKind scope, boolean push, String title, String body) {
    return new RoutingRule(
        eventType, role, scope, push, Optional.empty(), Set.of(), Optional.empty(), title, body);
  }

  boolean matches(java.util.Map<String, String> facts) {
    return whenFact.map(f -> whenValues.contains(facts.get(f))).orElse(true);
  }
}
