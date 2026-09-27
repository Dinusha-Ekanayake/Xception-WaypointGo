package com.waypoint.dispatch.identity.domain.policy;

import java.util.Map;

/**
 * The question put to the evaluator.
 *
 * @param resource what is being acted on, as {@code wpt:<module>:<type>:<id>}.
 *     May be null for an action that has no specific target.
 * @param context values conditions may test: {@code wpt:now}, {@code wpt:actorId},
 *     {@code wpt:depot}, {@code wpt:vehicleId}
 */
public record AccessRequest(String action, String resource, Map<String, String> context) {

  /**
   * An action with no specific target is normalised to {@code *}.
   *
   * <p>That makes the safe thing happen: an unscoped request matches only an
   * unscoped grant. A policy that allows {@code plan:Publish} on
   * {@code wpt:ref:depot:PELIYAGODA} does not answer "may this actor publish
   * plans in general", and answering yes would quietly widen a scoped grant.
   */
  public AccessRequest {
    resource = resource == null || resource.isBlank() ? "*" : resource;
    context = Map.copyOf(context);
  }

  public static AccessRequest of(String action, String resource) {
    return new AccessRequest(action, resource, Map.of());
  }
}
