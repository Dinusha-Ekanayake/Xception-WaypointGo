package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.identity.domain.McpReadPolicy;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.config.McpProperties;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import org.springframework.stereotype.Component;

/** Application decision point for the independent, revocable connection grant. */
@Component
public class McpAccessHandler {
  public static final String CONNECT = "mcp:Connect";
  public static final String RESOURCE = "wpt:mcp:connection:*";
  private final SessionRegistry sessions;
  private final PolicyDecisionPoint policies;
  private final AuditLog audit;
  private final McpProperties properties;
  private final Metrics metrics;

  public McpAccessHandler(SessionRegistry sessions, PolicyDecisionPoint policies,
      AuditLog audit, McpProperties properties, Metrics metrics) {
    this.sessions = sessions;
    this.policies = policies;
    this.audit = audit;
    this.properties = properties;
    this.metrics = metrics;
  }

  public void requireEnabled() {
    if (!properties.enabled()) {
      throw new DomainException(ErrorCode.FORBIDDEN, "MCP connections are disabled");
    }
  }

  public void requireResource(String token, String resource) {
    if (!sessions.matchesMcpResource(token, resource)
        || (resource != null && (!properties.remoteEnabled() || !resource.equals(properties.publicUrl())))) {
      throw new DomainException(ErrorCode.UNAUTHENTICATED, "Credential is not valid for this MCP resource");
    }
  }

  public SessionView require(String token, String method, String path) {
    SessionView session = sessions.resolveMcp(token).orElseThrow(
        () -> new DomainException(ErrorCode.UNAUTHENTICATED, "MCP credential is expired or revoked"));
    Actor actor = sessions.actorOf(session);
    // A caller can always end their own connection, even after policy revocation
    // or a feature switch. This permits no operation against anyone else's state.
    if ("POST".equals(method) && "/api/mcp/session/end".equals(path)) {
      return session;
    }
    if (!properties.enabled() || !McpReadPolicy.permits(method, path)) {
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), CONNECT,
          RESOURCE, properties.enabled() ? "MCP credential permits approved reads only" : "MCP disabled"));
      throw new DomainException(ErrorCode.FORBIDDEN, "MCP credential permits approved reads only");
    }
    requireGrant(actor);
    return session;
  }

  public void requireGrant(Actor actor) {
    policies.denyReason(actor, CONNECT, RESOURCE, null).ifPresent(reason -> {
      throw new DomainException(ErrorCode.FORBIDDEN, reason);
    });
  }

  public void recordRead(Actor actor, String action, String resource) {
    audit.recordStandalone(AuditEntry.allowed(actor.userId(), actor.deviceId(), action, resource, "read via MCP"));
  }

  /**
   * One finished MCP request (issue #140). Every request is counted and timed per
   * tool and outcome. A request that was authorized also gets an outcome row in
   * the audit log, beside its authorization row and under the same correlation
   * id, because an allowed read can still end in 404 or an error. Refusals are
   * already audited where they were refused.
   *
   * <p>Recording never fails the request: if the audit write fails the response
   * stands and the failure is counted, so the gap is visible (rule 9).
   *
   * @param session null when the credential itself was refused
   * @param remote whether the request came through the remote OAuth resource
   */
  public void recordOutcome(SessionView session, String method, String path, boolean remote, int status, long durationMs) {
    String tool = McpReadPolicy.toolOf(method, path);
    String outcome = McpReadPolicy.outcomeOf(status);
    String transport = remote ? "remote" : "local";
    metrics.increment("waypoint.mcp.calls", "tool", tool, "outcome", outcome, "transport", transport);
    metrics.record("waypoint.mcp.duration", durationMs, "tool", tool, "outcome", outcome);
    if (session == null || "unauthenticated".equals(outcome) || "denied".equals(outcome)) {
      return;
    }
    try {
      Actor actor = sessions.actorOf(session);
      String result =
          "{\"tool\":\"" + tool + "\",\"status\":" + status + ",\"outcome\":\"" + outcome
              + "\",\"durationMs\":" + durationMs + ",\"transport\":\"" + transport + "\"}";
      audit.recordStandalone(new AuditEntry(actor.userId(), actor.deviceId(), CONNECT, "wpt:mcp:tool:" + tool,
          "ALLOW", "MCP read finished: " + outcome, null, null, "tool", tool, null, result, null));
    } catch (RuntimeException failure) {
      metrics.increment("waypoint.mcp.outcome_audit_failed", "tool", tool);
    }
  }
}
