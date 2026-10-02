package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import org.springframework.stereotype.Component;

/** Authentication bookkeeping, with the same throttle and revocation as browser sign-in. */
@Component
public class McpSessionHandler {
  private final LoginHandler login;
  private final SessionRegistry sessions;
  private final McpAccessHandler access;
  private final AuditLog audit;

  public McpSessionHandler(LoginHandler login, SessionRegistry sessions, McpAccessHandler access, AuditLog audit) {
    this.login = login;
    this.sessions = sessions;
    this.access = access;
    this.audit = audit;
  }

  public String connect(String email, String password, String sourceIp) {
    access.requireEnabled();
    String token = login.loginMcp(email, password, sourceIp);
    var session = sessions.resolveMcp(token).orElseThrow();
    try {
      access.requireGrant(sessions.actorOf(session));
      audit.recordStandalone(AuditEntry.allowed(session.userId(), null,
          McpAccessHandler.CONNECT, McpAccessHandler.RESOURCE, "personal read-only connection"));
      return token;
    } catch (RuntimeException error) {
      sessions.revoke(token);
      throw error;
    }
  }

  public void disconnect(String token) {
    var session = access.require(token, "POST", "/api/mcp/session/end");
    sessions.revoke(token);
    audit.recordStandalone(AuditEntry.allowed(session.userId(), null,
        McpAccessHandler.CONNECT, McpAccessHandler.RESOURCE, "MCP connection revoked"));
  }
}
