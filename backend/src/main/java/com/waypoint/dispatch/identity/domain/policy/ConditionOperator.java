package com.waypoint.dispatch.identity.domain.policy;

import java.time.Instant;
import java.util.Locale;

/**
 * The condition operators supported at baseline.
 *
 * <p>Kept small on purpose. Each one is a place a policy can be subtly wrong, so
 * the set grows only when a real rule needs it.
 */
public enum ConditionOperator {
  STRING_EQUALS("StringEquals"),
  STRING_NOT_EQUALS("StringNotEquals"),
  STRING_LIKE("StringLike"),
  DATE_LESS_THAN("DateLessThan"),
  DATE_GREATER_THAN("DateGreaterThan"),
  BOOL("Bool");

  private final String wireName;

  ConditionOperator(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  public static ConditionOperator parse(String value) {
    for (ConditionOperator operator : values()) {
      if (operator.wireName.equalsIgnoreCase(value)) {
        return operator;
      }
    }
    throw new IllegalArgumentException("Unknown condition operator: " + value);
  }

  /** @param actual the value from the request context, absent when unknown */
  public boolean test(String actual, String expected) {
    if (actual == null) {
      // An unknown context key cannot satisfy a condition. Treating it as a pass
      // would grant access whenever a caller simply forgot to supply it.
      return false;
    }
    return switch (this) {
      case STRING_EQUALS -> actual.equals(expected);
      case STRING_NOT_EQUALS -> !actual.equals(expected);
      case STRING_LIKE -> new Pattern(expected).matches(actual);
      case DATE_LESS_THAN -> Instant.parse(actual).isBefore(Instant.parse(expected));
      case DATE_GREATER_THAN -> Instant.parse(actual).isAfter(Instant.parse(expected));
      case BOOL -> actual.toLowerCase(Locale.ROOT).equals(expected.toLowerCase(Locale.ROOT));
    };
  }
}
