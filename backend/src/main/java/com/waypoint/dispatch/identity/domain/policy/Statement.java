package com.waypoint.dispatch.identity.domain.policy;

import java.util.List;
import java.util.Map;

/**
 * One rule inside a policy document.
 *
 * @param sid a label. Carried into the decision so a denial can be explained by
 *     naming the statement responsible, rather than leaving a user staring at an
 *     empty screen.
 */
public record Statement(
    String sid,
    Effect effect,
    List<Pattern> actions,
    List<Pattern> resources,
    List<Condition> conditions) {

  public boolean matches(String action, String resource, Map<String, String> context) {
    return matchesAction(action) && matchesResource(resource) && conditionsHold(context);
  }

  private boolean matchesAction(String action) {
    return actions.stream().anyMatch(p -> p.matches(action));
  }

  private boolean matchesResource(String resource) {
    // A statement with no resource applies to everything the action covers.
    return resources.isEmpty() || resources.stream().anyMatch(p -> p.matches(resource));
  }

  /** Every clause must hold. One failing clause fails the statement. */
  private boolean conditionsHold(Map<String, String> context) {
    return conditions.stream().allMatch(c -> c.isSatisfiedBy(context));
  }
}
