package com.waypoint.dispatch.identity.domain.policy;

import java.util.List;

/**
 * A policy as authored: a version label and its statements.
 *
 * <p>Immutable, like the row that stores it. Changing a policy creates a new
 * version rather than editing this one, so a decision taken last March can still
 * be explained by the rules that were in force then.
 */
public record PolicyDocument(String version, List<Statement> statements) {

  public PolicyDocument {
    statements = List.copyOf(statements);
  }
}
