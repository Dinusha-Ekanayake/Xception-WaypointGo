package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.PolicyDecisionPoint;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.contract.SessionView;
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
 */
@Component
public class SessionRequestAuthorizer implements RequestAuthorizer {
  private final SessionRegistry sessions;
  private final PolicyDecisionPoint decisions;
  private final SessionCookie cookie;

  public SessionRequestAuthorizer(
      SessionRegistry sessions, PolicyDecisionPoint decisions, SessionCookie cookie) {
    this.sessions = sessions;
    this.decisions = decisions;
    this.cookie = cookie;
  }

  @Override
  public Actor require(HttpServletRequest request, String action, String resource) {
    SessionView session =
        sessions
            .resolve(cookie.read(request))
            .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
    Actor actor = sessions.actorOf(session);
    // The same call a command goes through, so a denied read is audited the same
    // way a denied write is. decide() answers without recording, which is not what
    // a refused request should do.
    Optional<String> denial = decisions.denyReason(actor, action, resource, null);
    if (denial.isPresent()) {
      throw new DomainException(ErrorCode.FORBIDDEN, denial.get());
    }
    return actor;
  }
}
