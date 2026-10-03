package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.identity.domain.McpReadPolicy;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
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

  public McpAccessHandler(SessionRegistry sessions, PolicyDecisionPoint policies,
      AuditLog audit, McpProperties properties) {
    this.sessions = sessions;
    this.policies = policies;
    this.audit = audit;
    this.properties = properties;
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
}
