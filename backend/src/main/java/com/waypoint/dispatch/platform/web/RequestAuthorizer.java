package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.shared.domain.Actor;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Authorizes a read.
 *
 * <p>The counterpart of {@code CommandAuthorizer}, and a port for the same
 * reason: a module's own web layer may not import identity, so the two are joined
 * by an interface platform owns. Identity supplies the implementation.
 *
 * <p>Reads need this as much as writes do. Policy is the first thing an attacker
 * would want to read, and so are outlets, fleet and accounts, so
 * {@code reference:Read} and {@code iam:ReadPolicy} are permissions like any
 * other. This never returns a partial answer: unauthenticated is {@code 401},
 * unauthorized is {@code 403} plus an audit row, never an empty list.
 */
public interface RequestAuthorizer {

  /**
   * @return the actor, once the action is permitted
   * @throws com.waypoint.dispatch.shared.error.DomainException {@code UNAUTHENTICATED} with no
   *     valid session, {@code FORBIDDEN} when policy denies, naming the statement that decided it
   */
  Actor require(HttpServletRequest request, String action, String resource);
}
