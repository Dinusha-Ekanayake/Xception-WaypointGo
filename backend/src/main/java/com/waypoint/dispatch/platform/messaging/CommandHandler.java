package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.shared.domain.Actor;

/**
 * One handler per command kind. Handlers live in a module's application layer,
 * which is the only layer allowed to open a transaction or decide authorization.
 */
public interface CommandHandler {

  /** The command kind this handles, for example {@code reference:Import}. */
  String kind();

  /** The action string checked against policy before this runs. */
  String action();

  /**
   * What this command acts on, so a scoped policy can be evaluated. Null when
   * the action has no specific target, which only an unscoped grant can allow.
   */
  default String resource(Command command) {
    return null;
  }

  Object handle(Actor actor, Command command);
}
