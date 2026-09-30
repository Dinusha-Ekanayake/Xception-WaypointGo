package com.waypoint.dispatch.identity.contract;

import java.util.List;
import java.util.UUID;

/**
 * Who is signed in, as other modules and the client see them.
 *
 * <p>Carries identity and scope, never permissions. "May this actor do X" is
 * answered by asking the decision point, not by a caller inspecting roles and
 * reaching its own conclusion, which is how authorization logic leaks out of the
 * one place that is supposed to own it.
 *
 * @param scope coarse boundaries as {@code depot:PELIYAGODA} or
 *     {@code outlet:OUT001}, the same grants row-level security filters on
 */
public record SessionView(
    UUID userId, String displayName, List<String> roles, List<String> scope, UUID deviceId) {

  public SessionView {
    roles = List.copyOf(roles);
    scope = List.copyOf(scope);
  }
}
