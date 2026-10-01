package com.waypoint.dispatch.shared.error;

import java.util.Objects;

/**
 * One failed constraint, as the API reports it.
 *
 * @param rule the rule identifier from docs/architecture/RULES-AND-POLICIES.md, such as
 *     {@code R-PLN-06}, or a stable key such as {@code order:Plase} for an unknown action
 * @param field the request field at fault, or {@code null} when the rule is not about one field
 * @param message what went wrong, in words a person can act on. Never personal data.
 */
public record Violation(String rule, String field, String message) {

  public Violation {
    Objects.requireNonNull(rule, "rule");
    message = message == null ? "" : message;
  }

  public static Violation of(String rule, String message) {
    return new Violation(rule, null, message);
  }

  public static Violation onField(String rule, String field, String message) {
    return new Violation(rule, field, message);
  }
}
