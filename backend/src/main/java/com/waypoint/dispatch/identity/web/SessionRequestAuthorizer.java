package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.PolicyDecisionPoint;
import com.waypoint.dispatch.identity.application.McpAccessHandler;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolve the session, then apply policy. One place, so no controller invents its
 * own version of the check and forgets the audit row.
 *
 * <p>Only loading reads use the PIN-switched operator. Other modules keep the
 * signed-in device account as their actor.
 */
@Component
public class SessionRequestAuthorizer implements RequestAuthorizer {
  private final SessionActorResolver actors;
  private final PolicyDecisionPoint decisions;
  private final McpAccessHandler mcp;

  public SessionRequestAuthorizer(SessionActorResolver actors, PolicyDecisionPoint decisions, McpAccessHandler mcp) {
    this.actors = actors;
    this.decisions = decisions;
    this.mcp = mcp;
  }

  @Override
  public Actor require(HttpServletRequest request, String action, String resource) {
    Actor actor =
        (resource != null && resource.startsWith("wpt:loading:")
                ? actors.resolveLoading(request)
                : actors.resolve(request))
            .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
    // An MCP connection is narrowed by its client scope first (R-IAM-34); the
    // scope never widens what the policy below allows.
    String mcpToken = (String) request.getAttribute(McpCredentialFilter.CREDENTIAL);
    if (mcpToken != null) {
      mcp.requireReadScope(mcpToken, actor, action);
    }
    // The same call a command goes through, so a denied read is audited the same
    // way a denied write is. decide() answers without recording, which is not what
    // a refused request should do.
    Optional<String> denial = decisions.denyReason(actor, action, resource, null);
    if (denial.isPresent()) {
      throw new DomainException(ErrorCode.FORBIDDEN, denial.get());
    }
    if (mcpToken != null) {
      mcp.requireGrant(actor);
      mcp.recordRead(actor, action, resource);
    }
    return actor;
  }
}
