package com.waypoint.dispatch.shared.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;

/**
 * A phone number as Waypoint keeps it: the digits, with an optional leading plus.
 *
 * <p>The spaces, dots, brackets and hyphens people type are dropped, so the same
 * number is never stored two ways, and 7 to 15 digits remain (the E.164 length).
 * One definition for every module that keeps a number: a person's own, and a
 * store's contact.
 */
public final class PhoneNumber {
  private PhoneNumber() {}

  /** The number's digits, or null when the input is blank. A malformed number is rejected under {@code rule}. */
  public static String normalise(String raw, String rule) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String compact = raw.trim().replaceAll("[\\s().-]", "");
    if (!compact.matches("\\+?[0-9]{7,15}")) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "A phone number is 7 to 15 digits, with an optional leading +", List.of(rule));
    }
    return compact;
  }
}
