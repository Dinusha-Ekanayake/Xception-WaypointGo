package com.waypoint.dispatch.platform.audit;

import java.util.UUID;

/**
 * One auditable fact.
 *
 * @param decision ALLOW or DENY. Denied attempts are audited too: a failed
 *     access attempt is the interesting one.
 * @param reason why, in words a person can read, for example the policy Sid that
 *     matched, or the constraint that was violated
 * @param commandId the command this row describes, when there was one, so the row
 *     can be found from the receipt and the receipt from the row
 * @param targetType what kind of thing was acted on, from the resource
 *     {@code wpt:<module>:<type>:<id>}
 * @param targetId which one
 * @param beforeState JSON of the target before the command, already redacted by
 *     {@link AuditRedactor}. Null when nobody captured it
 * @param afterState JSON of the outcome, already redacted
 * @param policyGeneration the policy generation the decision was taken under
 */
public record AuditEntry(
    UUID actorId,
    UUID deviceId,
    String action,
    String resource,
    String decision,
    String reason,
    String correlationId,
    UUID commandId,
    String targetType,
    String targetId,
    String beforeState,
    String afterState,
    Long policyGeneration) {

  public static AuditEntry allowed(
      UUID actorId, UUID deviceId, String action, String resource, String reason) {
    return new AuditEntry(
        actorId, deviceId, action, resource, "ALLOW", reason, null, null, null, null, null, null, null);
  }

  public static AuditEntry denied(
      UUID actorId, UUID deviceId, String action, String resource, String reason) {
    return new AuditEntry(
        actorId, deviceId, action, resource, "DENY", reason, null, null, null, null, null, null, null);
  }

  public AuditEntry withCorrelation(String correlationId) {
    return new AuditEntry(
        actorId, deviceId, action, resource, decision, reason, correlationId, commandId,
        targetType, targetId, beforeState, afterState, policyGeneration);
  }

  public AuditEntry withCommand(UUID commandId) {
    return new AuditEntry(
        actorId, deviceId, action, resource, decision, reason, correlationId, commandId,
        targetType, targetId, beforeState, afterState, policyGeneration);
  }

  /** Splits {@code resource} into target type and id when it has the shape {@code wpt:<module>:<type>:<id>}. */
  public AuditEntry withTargetFromResource() {
    if (resource == null) {
      return this;
    }
    String[] parts = resource.split(":", 4);
    if (parts.length != 4 || !parts[0].equals("wpt") || parts[3].isEmpty()) {
      return this;
    }
    return new AuditEntry(
        actorId, deviceId, action, resource, decision, reason, correlationId, commandId,
        parts[1] + ":" + parts[2], parts[3], beforeState, afterState, policyGeneration);
  }

  public AuditEntry withState(String beforeState, String afterState) {
    return new AuditEntry(
        actorId, deviceId, action, resource, decision, reason, correlationId, commandId,
        targetType, targetId, beforeState, afterState, policyGeneration);
  }

  public AuditEntry withPolicyGeneration(Long policyGeneration) {
    return new AuditEntry(
        actorId, deviceId, action, resource, decision, reason, correlationId, commandId,
        targetType, targetId, beforeState, afterState, policyGeneration);
  }
}
