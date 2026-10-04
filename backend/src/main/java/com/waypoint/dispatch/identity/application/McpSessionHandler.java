package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.domain.McpScopes;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Set;

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

  /**
   * @param scope the scopes asked for, space separated; blank is the default of
   *     every read and the safe writes (R-IAM-34)
   */
  public String connect(String email, String password, String sourceIp, String scope) {
    access.requireEnabled();
    Set<String> scopes = McpScopes.granted(scope).orElseThrow(() -> new DomainException(
        ErrorCode.VALIDATION_FAILED, "scope names none of " + McpScopes.format(Set.copyOf(McpScopes.supported()))));
    String token = login.loginMcp(email, password, sourceIp);
    var session = sessions.resolveMcp(token).orElseThrow();
    try {
      sessions.grantMcpScopes(token, scopes);
      access.requireGrant(sessions.actorOf(session));
      audit.recordStandalone(AuditEntry.allowed(session.userId(), null,
          McpAccessHandler.CONNECT, McpAccessHandler.RESOURCE, "personal connection with " + McpScopes.format(scopes)));
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
