package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.application.PolicyDecisionPoint;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.identity.web.SessionActorResolver;
import com.waypoint.dispatch.identity.web.SessionCookie;
import com.waypoint.dispatch.identity.web.SessionRequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SessionActorScopeTest {
  private final SessionRegistry sessions = mock(SessionRegistry.class);
  private final OperatorRegistry operators = mock(OperatorRegistry.class);
  private final PolicyDecisionPoint decisions = mock(PolicyDecisionPoint.class);
  private final SessionCookie cookie = mock(SessionCookie.class);
  private final SessionActorResolver resolver = new SessionActorResolver(sessions, operators, cookie);
  private final SessionRequestAuthorizer authorizer = new SessionRequestAuthorizer(resolver, decisions,
      mock(com.waypoint.dispatch.identity.application.McpAccessHandler.class));

  @Test
  void pinOperatorIsUsedOnlyForLoadingReads() {
    UUID accountId = UUID.randomUUID();
    UUID operatorId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    Actor account = new Actor(accountId, deviceId);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(AuthController.COOKIE, "session-token"));
    when(cookie.read(request)).thenReturn("session-token");
    SessionView session = new SessionView(accountId, "Device", List.of("loader", "dispatcher"), List.of(), deviceId);
    when(sessions.resolve("session-token")).thenReturn(Optional.of(session));
    when(sessions.actorOf(session)).thenReturn(account);
    when(operators.operatorOf("session-token")).thenReturn(Optional.of(
        new OperatorRegistry.Operator(operatorId, "Operator", Optional.empty(), Instant.now())));
    when(decisions.denyReason(any(), any(), any(), eq(null))).thenReturn(Optional.empty());

    assertEquals(account, authorizer.require(request, "ordering:Read", "wpt:order:outlet:OUT001"));
    assertEquals(account, resolver.resolve(request).orElseThrow());
    assertEquals(new Actor(operatorId, deviceId),
        authorizer.require(request, "loading:Read", "wpt:loading:depot:PEL"));
    verify(decisions).denyReason(account, "ordering:Read", "wpt:order:outlet:OUT001", null);
  }
}
