package com.waypoint.dispatch.issues.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What was done about an issue (architecture rule 8). A fixed vocabulary, so a
 * resolution can be counted and audited, with the free text in the note.
 *
 * <p>There is no return: goods disposition is recorded in the note, but no
 * returns workflow exists (A-10, R-EXE-12).
 */
public enum ResolutionAction {
  /** A replacement was loaded for a shortfall; Loading may recheck. */
  REPLACEMENT,
  /** A new order linked to the original was requested from Ordering. */
  REDELIVERY,
  /** The loss is accepted and written off. */
  WRITE_OFF,
  /** Investigated; nothing was wrong, or the evidence does not support a fault. */
  NO_FAULT_FOUND,
  OTHER;

  public static ResolutionAction parse(String value) {
    String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (code.equals("RETURN")) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "there is no returns workflow; record the goods disposition in the note",
          List.of("R-EXE-12"));
    }
    try {
      return valueOf(code);
    } catch (IllegalArgumentException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "action must be one of " + Arrays.toString(values()),
          List.of("R-ISS-02"));
    }
  }
}
