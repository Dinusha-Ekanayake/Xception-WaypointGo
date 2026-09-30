package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;

/**
 * One handler per command kind. Handlers live in a module's application layer,
 * which is the only layer allowed to open a transaction or decide authorization.
 *
 * <p>A handler runs inside the transaction the bus has already opened, under the
 * role it declares here. It must not open another.
 */
public interface CommandHandler {

  /** The command kind this handles, for example {@code reference:Import}. */
  String kind();

  /** The action string checked against policy before this runs. */
  String action();

  /**
   * The database role the transaction adopts. Declared by the handler rather
   * than passed in by the caller, so the web layer never has to know which
   * module owns a command kind.
   */
  ModuleRole moduleRole();

  /**
   * What this command acts on, so a scoped policy can be evaluated. Null when
   * the action has no specific target, which only an unscoped grant can allow.
   */
  default String resource(Command command) {
    return null;
  }

  Object handle(Actor actor, Command command);
}
