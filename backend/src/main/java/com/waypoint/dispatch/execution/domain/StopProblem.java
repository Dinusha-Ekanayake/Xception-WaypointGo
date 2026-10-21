package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Locale;

/**
 * R-EXE-32: what the driver reports at a stop that only the dispatcher may decide
 * about. The stop stays open; it ends by the dispatcher's Skip store (R-EXE-31),
 * never by the driver's "Not delivered".
 */
public enum StopProblem {
  /** Nobody at the store, or it is shut. */
  STORE_CLOSED,
  /** The van cannot unload: no space, no access, the store will not take it now. */
  CANNOT_UNLOAD;

  public String code() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static StopProblem parse(String value) {
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "problem must be STORE_CLOSED or CANNOT_UNLOAD", List.of("R-EXE-32"));
    }
  }

  /** The failure reasons a driver no longer records: these situations are reported instead. */
  public static boolean reportedInstead(FailureReason reason) {
    return reason == FailureReason.OUTLET_CLOSED || reason == FailureReason.ACCESS_BLOCKED;
  }
}
