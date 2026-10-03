package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.shared.domain.Actor;

/**
 * The port the command bus asks before running anything.
 *
 * <p>Implemented by the identity module's policy decision point. Until that
 * exists there is no implementation, and the bus denies every command: an
 * unauthorized system must fail closed, never open.
 */
public interface CommandAuthorizer {

  /**
   * @param resource what is being acted on, as {@code wpt:<module>:<type>:<id>}, or null
   *     when the action has no specific target
   * @return a reason when denied, or empty when allowed
   */
  java.util.Optional<String> denyReason(
      Actor actor, String action, String resource, Command command);

  /**
   * The same question, asked again inside the command's transaction. What was
   * true a moment ago may have been revoked while the command waited for a
   * connection, and only an answer given inside the transaction is consistent
   * with what the command then reads and writes.
   *
   * <p>Must not write: a refusal rolls the transaction back, and the bus records
   * it afterwards.
   *
   * @return a reason when the permission no longer holds, or empty when it does
   */
  default java.util.Optional<String> denyReasonInTransaction(
      Actor actor, String action, String resource, Command command) {
    return denyReason(actor, action, resource, command);
  }

  /**
   * The policy generation this authorizer is deciding under, stamped on the audit
   * row so a decision can later be tied to the rules in force when it was taken
   * (POL-03). Empty when the authorizer has no notion of one.
   */
  default java.util.OptionalLong policyGeneration() {
    return java.util.OptionalLong.empty();
  }
}
