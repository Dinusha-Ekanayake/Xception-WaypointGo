package com.waypoint.dispatch.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * What a handler wants recorded about the change it is making, scoped to the one
 * command the bus is running on this thread.
 *
 * <p>The bus fills in everything it can know on its own: the command, the target
 * (from the handler's resource) and the outcome (from the result). What only the
 * handler knows is the state before, because only it reads the row it is about to
 * change. A handler that already loads that row calls {@link #before} with it and
 * the snapshot lands in the audit row, redacted. A handler that does not simply
 * leaves {@code before_state} empty; nothing breaks.
 *
 * <p>A thread-local because the alternative is changing the signature of every
 * handler in every module. It is opened and closed by the bus around exactly one
 * handler call, so it cannot leak into the next command on a pooled thread.
 */
public final class AuditContext {
  private static final ThreadLocal<AuditContext> CURRENT = new ThreadLocal<>();

  private final ObjectMapper mapper;
  private final AuditContext enclosing;
  private JsonNode before;
  private JsonNode after;

  private AuditContext(ObjectMapper mapper, AuditContext enclosing) {
    this.mapper = mapper;
    this.enclosing = enclosing;
  }

  /**
   * Opens the context for one command. The bus closes it in a {@code finally}.
   * A command dispatched from inside another (a sync batch replays many) gets a
   * context of its own and hands the outer one back when it closes.
   */
  public static AuditContext open(ObjectMapper mapper) {
    AuditContext context = new AuditContext(mapper, CURRENT.get());
    CURRENT.set(context);
    return context;
  }

  public void close() {
    if (enclosing == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(enclosing);
    }
  }

  /** Records the target's state before the change. A no-op outside a command. */
  public static void before(Object snapshot) {
    AuditContext context = CURRENT.get();
    if (context != null && snapshot != null) {
      context.before = context.mapper.valueToTree(snapshot);
    }
  }

  /**
   * Overrides the outcome the bus would record, for a handler whose result is not
   * the state of the thing it changed.
   */
  public static void after(Object snapshot) {
    AuditContext context = CURRENT.get();
    if (context != null && snapshot != null) {
      context.after = context.mapper.valueToTree(snapshot);
    }
  }

  public Optional<JsonNode> capturedBefore() {
    return Optional.ofNullable(before);
  }

  public Optional<JsonNode> capturedAfter() {
    return Optional.ofNullable(after);
  }
}
