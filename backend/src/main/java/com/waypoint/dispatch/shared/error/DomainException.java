package com.waypoint.dispatch.shared.error;

import java.util.List;

/**
 * A rule was broken. Carries the violated rule identifiers from
 * docs/architecture/RULES-AND-POLICIES.md, so the API can report which
 * constraint failed rather than a bare message.
 */
public class DomainException extends RuntimeException {
  private final ErrorCode code;
  private final List<String> violations;

  public DomainException(ErrorCode code, String message) {
    this(code, message, List.of());
  }

  public DomainException(ErrorCode code, String message, List<String> violations) {
    super(message);
    this.code = code;
    this.violations = List.copyOf(violations);
  }

  public ErrorCode code() {
    return code;
  }

  /** Rule identifiers such as R-PLN-06. Empty when the failure is not rule-specific. */
  public List<String> violations() {
    return violations;
  }
}
