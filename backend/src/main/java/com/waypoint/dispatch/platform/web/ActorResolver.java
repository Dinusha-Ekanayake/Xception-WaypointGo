package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.shared.domain.Actor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Turns a request into the actor making it.
 *
 * <p>A port, for the same reason {@code CommandAuthorizer} is one: the command
 * endpoint is platform plumbing and must not import the identity module. Identity
 * supplies the implementation, so how a caller is recognised can change without
 * the endpoint knowing.
 *
 * <p>With no implementation wired there is no actor, and the endpoint answers
 * {@code 401} rather than guessing one.
 */
public interface ActorResolver {

  /** Empty when the request carries no valid session. */
  Optional<Actor> resolve(HttpServletRequest request);
}
