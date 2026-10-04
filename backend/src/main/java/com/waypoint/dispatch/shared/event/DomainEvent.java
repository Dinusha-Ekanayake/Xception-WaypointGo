package com.waypoint.dispatch.shared.event;

/**
 * Something that happened in one module that another module may react to.
 *
 * <p>Events are the default way modules connect (SYSTEM-ARCHITECTURE §5): a
 * consequence travels by event, a precondition by contract query. Every event is
 * a record in its producer's {@code contract} package, so a consumer depends on
 * the payload shape and never on the producer's internals.
 *
 * <p>Each event record also declares {@code public static final String TYPE},
 * so a subscriber can name the type without constructing an instance. The type
 * is lower case and dotted, {@code <aggregate>.<what_happened>}, for example
 * {@code order.placed}. A payload change that removes or renames a field needs
 * a new {@link #version()}; adding a field does not.
 */
public interface DomainEvent {

  /** The stable, dotted event type, for example {@code plan.published}. */
  String type();

  /** The payload schema version. Override only when a field is removed or renamed. */
  default int version() {
    return 1;
  }

  /** What kind of thing changed, for example {@code order} or {@code trip}. */
  String aggregateType();

  /** Which one changed. Text because some aggregates have natural keys. */
  String aggregateId();
}
