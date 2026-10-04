package com.waypoint.dispatch.warehouse.domain;

import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Ambiguous;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Attempt;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Found;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Match;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What the retry job does with a placement whose outcome it does not know, once
 * it has looked at the warehouse (STK-09).
 *
 * <ul>
 *   <li>Found, same goods as wanted: adopt it, never place again
 *   <li>Found, older goods or no longer wanted: cancel it, then place if wanted
 *   <li>Ambiguous: stop, raise it; a guess double-reserves or steals stock
 *   <li>Not found, last attempt still recent: wait, the warehouse may be slow to list it
 *   <li>Not found, window passed: nothing was created; place if still wanted
 * </ul>
 */
public final class RetryPolicy {
  private RetryPolicy() {}

  public enum Action {
    ADOPT,
    CANCEL_THEN_PLACE,
    CANCEL_ONLY,
    RAISE_AMBIGUOUS,
    WAIT,
    PLACE,
    GIVE_UP
  }

  public record Decision(Action action, Optional<WarehouseOrder> found) {}

  public static Decision decide(Placement placement, Match match, Instant now, Duration window) {
    if (match instanceof Ambiguous) {
      return new Decision(Action.RAISE_AMBIGUOUS, Optional.empty());
    }
    if (match instanceof Found f) {
      boolean sameGoods =
          OrphanMatcher.fingerprint(f.order().items().stream()
                  .map(i -> new com.waypoint.dispatch.warehouse.contract.StockPort.StockLine(
                      i.productId(), i.requestedQuantity()))
                  .toList())
              .equals(OrphanMatcher.fingerprint(placement.lines()));
      if (placement.compensateOnly()) {
        return new Decision(Action.CANCEL_ONLY, Optional.of(f.order()));
      }
      return new Decision(sameGoods ? Action.ADOPT : Action.CANCEL_THEN_PLACE, Optional.of(f.order()));
    }
    Optional<Instant> last = lastSent(placement.attempts());
    if (last.isPresent() && now.isBefore(last.get().plus(window))) {
      return new Decision(Action.WAIT, Optional.empty());
    }
    return new Decision(placement.compensateOnly() ? Action.GIVE_UP : Action.PLACE, Optional.empty());
  }

  /** Backoff for a placement or status call that failed again: 1, 2, 4 ... capped at 30 minutes. */
  public static Instant nextAttempt(Instant now, int failures) {
    long minutes = Math.min(30, 1L << Math.min(failures, 5));
    return now.plus(Duration.ofMinutes(minutes));
  }

  private static Optional<Instant> lastSent(List<Attempt> attempts) {
    return attempts.stream().map(Attempt::sentAt).max(Instant::compareTo);
  }
}
