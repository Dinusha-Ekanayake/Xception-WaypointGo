package com.waypoint.dispatch.loading.domain;

import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import java.util.UUID;

/**
 * One item line of one order on a trip: a product and its unit count, and the
 * state of its latest check. Immutable.
 *
 * @param attempt 0 while never checked; each check or undo adds one
 * @param loadedUnits units of this line on the vehicle by the latest attempt
 */
public record ItemLine(
    UUID orderId,
    int lineNo,
    int stopSequence,
    String productId,
    int units,
    CheckStatus status,
    int attempt,
    int loadedUnits) {

  public ItemLine {
    if (units <= 0) {
      throw new IllegalArgumentException("An item line has at least one unit");
    }
    if (loadedUnits < 0 || loadedUnits > units) {
      throw new IllegalArgumentException("Loaded units must be between 0 and " + units);
    }
  }

  /** A line nobody has checked yet. */
  public static ItemLine unchecked(UUID orderId, int lineNo, int stopSequence, String productId, int units) {
    return new ItemLine(orderId, lineNo, stopSequence, productId, units, CheckStatus.PENDING, 0, 0);
  }

  public boolean pending() {
    return status == CheckStatus.PENDING;
  }

  public boolean flagged() {
    return isFlag(status);
  }

  /** The line after one more attempt. */
  public ItemLine next(CheckStatus to, int loaded) {
    return new ItemLine(orderId, lineNo, stopSequence, productId, units, to, attempt + 1, loaded);
  }

  /** Missing, damaged and doesn't fit are flags: the item is not loaded and people are told. */
  public static boolean isFlag(CheckStatus status) {
    return status == CheckStatus.MISSING
        || status == CheckStatus.DAMAGED
        || status == CheckStatus.SHORT
        || status == CheckStatus.DOES_NOT_FIT;
  }
}
