package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.platform.web.ActorResolver;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Identity's answer to "who is calling": the session cookie, resolved server
 * side on every request.
 *
 * <p>Sessions are opaque and looked up, not signed and trusted, so disabling an
 * account or changing a policy takes effect on the next request rather than at
 * token expiry.
 *
 * <p>On a shared loader device the actor is the operator who switched in with
 * their PIN, not the supervisor who signed the device in, so every check and
 * flag names the person who touched the goods (R-RCP-08, R-IAM-25).
 */
@Component
public class SessionActorResolver implements ActorResolver {
  private final SessionRegistry sessions;
  private final OperatorRegistry operators;
  private final SessionCookie cookie;

  public SessionActorResolver(SessionRegistry sessions, OperatorRegistry operators, SessionCookie cookie) {
    this.sessions = sessions;
    this.operators = operators;
    this.cookie = cookie;
  }

  @Override
  public Optional<Actor> resolve(HttpServletRequest request) {
    String mcp = (String) request.getAttribute(McpCredentialFilter.CREDENTIAL);
    if (mcp != null) {
      return sessions.resolveMcp(mcp).map(sessions::actorOf);
    }
    String token = cookie.read(request);
    return sessions.resolve(token).map(sessions::actorOf);
  }

  /** Only loading reads may use the PIN-switched operator as their actor. */
  public Optional<Actor> resolveLoading(HttpServletRequest request) {
    if (request.getAttribute(McpCredentialFilter.CREDENTIAL) != null) {
      return resolve(request);
    }
    String token = cookie.read(request);
    return sessions.resolve(token).map(session ->
        operators.operatorOf(token)
            .map(op -> new Actor(op.userId(), session.deviceId()))
            .orElseGet(() -> sessions.actorOf(session)));
  }

  @Override
  public Optional<Actor> resolveDevice(HttpServletRequest request) {
    String token = cookie.read(request);
    return sessions.resolve(token).map(sessions::actorOf);
  }

  @Override
  public String sessionCredential(HttpServletRequest request) {
    return cookie.read(request);
  }

  @Override
  public Optional<Actor> resolveCommand(HttpServletRequest request, Command command) {
    if (command == null || !command.kind().startsWith("loading:")) {
      return resolve(request);
    }
    String token = cookie.read(request);
    var session = sessions.resolve(token)
        .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
    if (!session.roles().contains(OperatorRegistry.LOADER_ROLE)) {
      return Optional.of(sessions.actorOf(session));
    }
    var operator = operators.operatorOf(token).orElseThrow(
        () -> new DomainException(ErrorCode.FORBIDDEN, "Device locked, switch user", java.util.List.of("R-IAM-25")));
    UUID actingUserId = command.actingUserId();
    if (actingUserId == null || !operator.userId().equals(actingUserId)) {
      throw new DomainException(ErrorCode.FORBIDDEN,
          "The command must identify the loader who entered their PIN", java.util.List.of("R-IAM-25"));
    }
    return Optional.of(new Actor(operator.userId(), session.deviceId()));
  }
}
