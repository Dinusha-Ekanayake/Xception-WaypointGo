package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.platform.web.ActorResolver;
import com.waypoint.dispatch.shared.domain.Actor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Identity's answer to "who is calling": the session cookie, resolved server
 * side on every request.
 *
 * <p>Sessions are opaque and looked up, not signed and trusted, so disabling an
 * account or changing a policy takes effect on the next request rather than at
 * token expiry.
 */
@Component
public class SessionActorResolver implements ActorResolver {
  private final SessionRegistry sessions;
  private final SessionCookie cookie;

  public SessionActorResolver(SessionRegistry sessions, SessionCookie cookie) {
    this.sessions = sessions;
    this.cookie = cookie;
  }

  @Override
  public Optional<Actor> resolve(HttpServletRequest request) {
    return sessions.resolve(cookie.read(request)).map(sessions::actorOf);
  }
}
