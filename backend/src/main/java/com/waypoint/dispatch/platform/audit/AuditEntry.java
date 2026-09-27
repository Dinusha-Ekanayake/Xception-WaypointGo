package com.waypoint.dispatch.platform.audit;

import java.util.UUID;

/**
 * One auditable fact.
 *
 * @param decision ALLOW or DENY. Denied attempts are audited too: a failed
 *     access attempt is the interesting one.
 * @param reason why, in words a person can read, for example the policy Sid that
 *     matched, or the constraint that was violated
 */
public record AuditEntry(
    UUID actorId,
    UUID deviceId,
    String action,
    String resource,
    String decision,
    String reason,
    String correlationId) {

  public static AuditEntry allowed(
      UUID actorId, UUID deviceId, String action, String resource, String reason) {
    return new AuditEntry(actorId, deviceId, action, resource, "ALLOW", reason, null);
  }

  public static AuditEntry denied(
      UUID actorId, UUID deviceId, String action, String resource, String reason) {
    return new AuditEntry(actorId, deviceId, action, resource, "DENY", reason, null);
  }
}
