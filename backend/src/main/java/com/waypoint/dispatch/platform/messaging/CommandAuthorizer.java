package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.shared.domain.Actor;

/**
 * The port the command bus asks before running anything.
 *
 * <p>Implemented by the identity module's policy decision point. Until that
 * exists there is no implementation, and the bus denies every command: an
 * unauthorized system must fail closed, never open.
 */
public interface CommandAuthorizer {

  /**
   * @param resource what is being acted on, as {@code wpt:<module>:<type>:<id>}, or null
   *     when the action has no specific target
   * @return a reason when denied, or empty when allowed
   */
  java.util.Optional<String> denyReason(
      Actor actor, String action, String resource, Command command);
}
