package com.waypoint.dispatch.shared.error;

import java.util.List;
import java.util.Optional;

/**
 * A rule was broken. Carries the violated constraints, each naming a rule from
 * docs/architecture/RULES-AND-POLICIES.md, so the API can report which constraint
 * failed rather than a bare message.
 *
 * <p>The message becomes the problem {@code detail} a client sees. It must never
 * carry personal data such as an email address.
 */
public class DomainException extends RuntimeException {
  private final ErrorCode code;
  private final List<Violation> violations;
  private final Long retryAfterSeconds;

  public DomainException(ErrorCode code, String message) {
    this(code, message, List.of(), null);
  }

  /** Rule identifiers only; each becomes a violation carrying this exception's message. */
  public DomainException(ErrorCode code, String message, List<String> rules) {
    this(code, message, rules.stream().map(rule -> Violation.of(rule, message)).toList(), null);
  }

  public static DomainException withViolations(
      ErrorCode code, String message, List<Violation> violations) {
    return new DomainException(code, message, violations, null);
  }

  /** {@link ErrorCode#RATE_LIMITED} with the delay a client should wait before retrying. */
  public static DomainException rateLimited(String message, long retryAfterSeconds) {
    return new DomainException(ErrorCode.RATE_LIMITED, message, List.of(), retryAfterSeconds);
  }

  protected DomainException(
      ErrorCode code, String message, List<Violation> violations, Long retryAfterSeconds) {
    super(message);
    this.code = code;
    this.violations = List.copyOf(violations);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public ErrorCode code() {
    return code;
  }

  /** Empty when the failure is not rule-specific. */
  public List<Violation> violations() {
    return violations;
  }

  /** The rule identifiers alone, such as R-PLN-06. */
  public List<String> rules() {
    return violations.stream().map(Violation::rule).toList();
  }

  public Optional<Long> retryAfterSeconds() {
    return Optional.ofNullable(retryAfterSeconds);
  }
}
