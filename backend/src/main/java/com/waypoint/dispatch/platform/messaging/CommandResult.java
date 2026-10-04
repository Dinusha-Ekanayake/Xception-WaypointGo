package com.waypoint.dispatch.platform.messaging;

/**
 * What a command produced, and whether this execution actually did the work.
 *
 * @param replayed true when a stored receipt was returned instead of running the
 *     handler again. Surfaced so a client can tell "done" from "done just now",
 *     and so the duplicate-suppression metric means something.
 */
public record CommandResult(Object value, boolean replayed) {

  public static CommandResult executed(Object value) {
    return new CommandResult(value, false);
  }

  public static CommandResult replayed(Object value) {
    return new CommandResult(value, true);
  }
}
