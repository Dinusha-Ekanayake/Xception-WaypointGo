package com.waypoint.dispatch.loading.domain;

import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.loading.contract.LoadingViews.SessionStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The dock work on one trip, against the plan version it was built from.
 *
 * <p>Immutable and free of persistence. Each operation either refuses with the
 * rule it breaks, or returns the lines it changed so the repository can append
 * one new attempt per line. The version guard is the repository's.
 *
 * <p>The rules, from RULES-AND-POLICIES:
 *
 * <ul>
 *   <li>R-LOD-11: one loader holds a trip at a time; only the holder writes to it.
   *   <li>R-LOD-02: a missing, damaged or ill-fitting item is flagged before
 *       departure. A flagged item is not loaded, and loading carries on.
 *   <li>R-LOD-07: release is refused while any item is still unchecked. Flagged
 *       items travel as recorded exceptions; they do not block.
 *   <li>D-L: items load in the reverse of the stop sequence.
 * </ul>
 */
public record LoadingSession(
    UUID tripId,
    Phase phase,
    Optional<Holder> holder,
    boolean chilled,
    List<ItemLine> items,
    long rowVersion) {

  public enum Phase {
    NOT_STARTED,
    IN_PROGRESS,
    RELEASED
  }

  /** @param employeeCode the dock badge, for example LDR-00038, when the account has one */
  public record Holder(UUID userId, String name, Optional<String> employeeCode, Instant since) {}

  /** What an operation changed: the new session and the lines to append an attempt for. */
  public record Change(LoadingSession session, List<ItemLine> changed) {
    public Change {
      changed = List.copyOf(changed);
    }
  }

  public LoadingSession {
    items = List.copyOf(items);
    if (phase == Phase.RELEASED && holder.isPresent()) {
      throw new IllegalStateException("A released trip has no holder");
    }
  }

  // ---- taking and letting go ---------------------------------------------

  /**
   * Take the trip. Taking a trip you already hold is a no-op, so a retried tap
   * does not fail. Taking someone else's is refused: they hand it back first.
   *
   * @return the change; {@code changed} is always empty
   */
  public Change take(UUID actor, String name, Optional<String> employeeCode, Instant now) {
    requireNotReleased();
    if (holder.isPresent()) {
      if (holder.get().userId().equals(actor)) {
        return new Change(this, List.of());
      }
      Holder other = holder.get();
      throw new DomainException(
          ErrorCode.CONFLICT,
          "This trip is in use by " + other.name() + other.employeeCode().map(c -> " (" + c + ")").orElse("")
              + ". One loader per trip: ask them to hand it back, or call dispatch.",
          List.of("R-LOD-11"));
    }
    Holder taker = new Holder(actor, name, employeeCode, now);
    return new Change(
        new LoadingSession(tripId, Phase.IN_PROGRESS, Optional.of(taker), chilled, items, rowVersion),
        List.of());
  }

  /** Whether this take is the first one, which is when loading.started is announced. */
  public boolean neverStarted() {
    return phase == Phase.NOT_STARTED;
  }

  public Change handBack(UUID actor) {
    requireHolder(actor);
    return new Change(
        new LoadingSession(tripId, phase, Optional.empty(), chilled, items, rowVersion), List.of());
  }

  // ---- checking ------------------------------------------------------------

  /**
   * Tick a line as loaded, or undo a tick with PENDING. Without a line, every
   * line of the order that is still unchecked is ticked. A flagged line may be
   * ticked if the item turns up; the flag stays on record.
   */
  public Change check(UUID actor, UUID orderId, Optional<Integer> lineNo, CheckStatus to) {
    requireHolder(actor);
    if (to != CheckStatus.LOADED && to != CheckStatus.PENDING) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "A check is LOADED or PENDING (undo). Report " + to + " as an issue instead.");
    }
    List<ItemLine> targets = targets(orderId, lineNo);
    List<ItemLine> changed = new ArrayList<>();
    for (ItemLine line : targets) {
      if (to == CheckStatus.LOADED) {
        if (line.status() == CheckStatus.LOADED) {
          continue; // already on the vehicle: a retried tap changes nothing
        }
        if (lineNo.isEmpty() && line.flagged()) {
          continue; // ticking a whole order never silently clears a flag
        }
        changed.add(line.next(CheckStatus.LOADED, line.units()));
      } else {
        if (line.status() != CheckStatus.LOADED) {
          if (lineNo.isPresent()) {
            throw new DomainException(
                ErrorCode.CONFLICT, "Only a loaded item can be undone; this one is " + line.status() + ".");
          }
          continue;
        }
        changed.add(line.next(CheckStatus.PENDING, 0));
      }
    }
    return apply(changed);
  }

  /**
   * Flag an item before departure. It is not loaded; the units that did arrive
   * stay counted for a partial line. Without a line, every line of the order that
   * is not already flagged is flagged in full.
   *
   * @param missingUnits for one line, how many of its units are affected
   */
  public Change flag(UUID actor, UUID orderId, Optional<Integer> lineNo, CheckStatus kind, int missingUnits) {
    requireHolder(actor);
    if (!ItemLine.isFlag(kind)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "An issue is SHORT, MISSING, DAMAGED or DOES_NOT_FIT, not " + kind + ".");
    }
    if (missingUnits <= 0) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Say how many units are affected.");
    }
    // Short (Figma 03, "Fewer packages than picked") is one item with some units
    // missing. A whole order short, or an item with none arrived, is Missing.
    if (kind == CheckStatus.SHORT) {
      if (lineNo.isEmpty()) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "Short is for one item. If the whole order is not here, report it missing.");
      }
      if (missingUnits >= targets(orderId, lineNo).get(0).units()) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "None of this item arrived, so report it missing, not short.");
      }
    }
    List<ItemLine> changed = new ArrayList<>();
    if (lineNo.isPresent()) {
      ItemLine line = targets(orderId, lineNo).get(0);
      if (missingUnits > line.units()) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED,
            "This item has " + line.units() + " units; " + missingUnits + " can't be affected.");
      }
      changed.add(line.next(kind, line.units() - missingUnits));
    } else {
      for (ItemLine line : targets(orderId, lineNo)) {
        if (!line.flagged()) {
          changed.add(line.next(kind, 0));
        }
      }
      if (changed.isEmpty()) {
        throw new DomainException(ErrorCode.CONFLICT, "Every item of this order is already flagged.");
      }
    }
    return apply(changed);
  }

  /** Units a flag takes off the vehicle, for the shortfall record. */
  public static int unitsAffected(List<ItemLine> flagged) {
    return flagged.stream().mapToInt(l -> l.units() - l.loadedUnits()).sum();
  }

  // ---- release -------------------------------------------------------------

  /** R-LOD-07: every item checked on this plan version, then the checklist. */
  public LoadingSession release(UUID actor, ReleaseChecklist checklist) {
    requireHolder(actor);
    long open = items.stream().filter(ItemLine::pending).count();
    if (open > 0) {
      long orders = items.stream().filter(ItemLine::pending).map(ItemLine::orderId).distinct().count();
      throw new DomainException(
          ErrorCode.CONFLICT,
          "Can't release yet: " + open + (open == 1 ? " item" : " items") + " in " + orders
              + (orders == 1 ? " order" : " orders") + " still to load, or report what's missing.",
          List.of("R-LOD-07"));
    }
    checklist.requireSatisfied();
    return new LoadingSession(tripId, Phase.RELEASED, Optional.empty(), chilled, items, rowVersion);
  }

  // ---- reading -------------------------------------------------------------

  /** What the dock board and manifest show. */
  public SessionStatus status() {
    return statusOf(
        phase, items.stream().anyMatch(ItemLine::pending), items.stream().anyMatch(ItemLine::flagged));
  }

  /** The one rule for a session's status, shared with the dock board's summary query. */
  public static SessionStatus statusOf(Phase phase, boolean anyPending, boolean anyFlagged) {
    return switch (phase) {
      case RELEASED -> SessionStatus.COMPLETED;
      case NOT_STARTED -> SessionStatus.NOT_STARTED;
      case IN_PROGRESS -> {
        if (!anyPending) {
          yield SessionStatus.READY;
        }
        yield anyFlagged ? SessionStatus.BLOCKED : SessionStatus.IN_PROGRESS;
      }
    };
  }

  /** D-L: the last stop is loaded first. */
  public static List<Integer> loadOrder(List<Integer> stopSequences) {
    return stopSequences.stream().distinct().sorted(Comparator.reverseOrder()).toList();
  }

  // ---- internals -----------------------------------------------------------

  private List<ItemLine> targets(UUID orderId, Optional<Integer> lineNo) {
    List<ItemLine> found =
        items.stream()
            .filter(l -> l.orderId().equals(orderId))
            .filter(l -> lineNo.isEmpty() || l.lineNo() == lineNo.get())
            .toList();
    if (found.isEmpty()) {
      throw new DomainException(
          ErrorCode.NOT_FOUND,
          lineNo.isPresent()
              ? "Item " + lineNo.get() + " is not on this order in the current plan."
              : "This order is not on the trip in the current plan.",
          List.of("R-LOD-03"));
    }
    return found;
  }

  private Change apply(List<ItemLine> changed) {
    List<ItemLine> next = new ArrayList<>(items.size());
    for (ItemLine line : items) {
      ItemLine replacement = line;
      for (ItemLine c : changed) {
        if (c.orderId().equals(line.orderId()) && c.lineNo() == line.lineNo()) {
          replacement = c;
        }
      }
      next.add(replacement);
    }
    return new Change(new LoadingSession(tripId, phase, holder, chilled, next, rowVersion), changed);
  }

  private void requireNotReleased() {
    if (phase == Phase.RELEASED) {
      throw new DomainException(ErrorCode.CONFLICT, "This trip has already been released.");
    }
  }

  private void requireHolder(UUID actor) {
    requireNotReleased();
    if (holder.isEmpty()) {
      throw new DomainException(
          ErrorCode.CONFLICT, "Take the trip first. One loader per trip.", List.of("R-LOD-11"));
    }
    if (!holder.get().userId().equals(actor)) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "This trip is locked to " + holder.get().name() + ". Only they can change it until they hand it back.",
          List.of("R-LOD-11"));
    }
  }
}
