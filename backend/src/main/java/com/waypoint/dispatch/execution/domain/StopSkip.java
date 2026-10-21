package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The dispatcher takes a stop off a trip on the road (delivery:SkipStop,
 * delivery:EndTrip): what happens to its order and why (rule 8, R-EXE-31).
 *
 * <p>A skip is the dispatcher's decision and the same order moves on: to the
 * next operating day, or cancelled with its goods back at the depot. A driver's
 * "not delivered" is a failure instead, and its redelivery is a new order
 * (A-24). Kept beside the stop, once, and never rewritten.
 */
public final class StopSkip {
  public static final int REASON_MIN = 3;
  public static final int REASON_MAX = 500;

  private StopSkip() {}

  public enum Then {
    /** Back to the depot and onto the next operating day's plan, the same order. */
    NEXT_DAY,
    /** Back to the depot and cancelled; depot staff put the stock right by hand. */
    CANCEL;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Then parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED,
            "then must be one of " + Arrays.stream(values()).map(Then::name).collect(Collectors.joining(", ")));
      }
    }
  }

  /** The reason as kept: trimmed, 3 to 500 characters. */
  public static String reason(String text) {
    String trimmed = text == null ? "" : text.trim();
    if (trimmed.length() < REASON_MIN || trimmed.length() > REASON_MAX) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "Say why in " + REASON_MIN + " to " + REASON_MAX + " characters",
          List.of("R-EXE-31"));
    }
    return trimmed;
  }
}
