package com.waypoint.dispatch.identity.domain;

import com.waypoint.dispatch.shared.domain.PhoneNumber;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;

/**
 * What a person may say about themselves (R-IAM-32), normalised and checked.
 *
 * <p>A name is trimmed with its inner spaces collapsed, so " Nuwan   Perera " and
 * "Nuwan Perera" are one name. A phone number is kept as its digits with an
 * optional leading plus: the spaces, dots, brackets and hyphens people type are
 * dropped, so the same number is never stored two ways. A blank number clears it.
 */
public final class ProfileFields {
  public static final String RULE = "R-IAM-32";
  public static final int NAME_MAX = 80;

  private ProfileFields() {}

  public static String name(String raw) {
    String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    if (name.isEmpty()) {
      throw invalid("A name is required");
    }
    if (name.length() > NAME_MAX) {
      throw invalid("A name is at most " + NAME_MAX + " characters");
    }
    return name;
  }

  /** The digits of a phone number with an optional leading +, or null when blank. */
  public static String phone(String raw) {
    return PhoneNumber.normalise(raw, RULE);
  }

  private static DomainException invalid(String message) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, message, List.of(RULE));
  }
}
