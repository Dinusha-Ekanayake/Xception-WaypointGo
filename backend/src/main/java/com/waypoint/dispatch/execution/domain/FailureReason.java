package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Why a delivery did not happen. A closed list, so failures can be counted by
 * reason; what happened to the goods goes in the disposition note (R-EXE-12).
 */
public enum FailureReason {
  OUTLET_CLOSED,
  REFUSED,
  /** A mall takes goods only inside its window; a late arrival cannot unload (EXE-20). */
  MALL_WINDOW_CLOSED,
  ACCESS_BLOCKED,
  VEHICLE_BREAKDOWN,
  GOODS_DAMAGED,
  OTHER;

  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static FailureReason parse(String value) {
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "reason must be one of "
              + Arrays.stream(values()).map(FailureReason::code).collect(Collectors.joining(", ")));
    }
  }
}
