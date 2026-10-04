package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.platform.web.RequestValues;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign in, sign out, and who am I.
 *
 * <p>Thin on purpose: it moves values between HTTP and the application layer and
 * decides nothing. The session token never appears in a response body, only in a
 * cookie the browser cannot read, so a cross-site script cannot lift it.
 */
@RestController
@RequestMapping("/api")
public class AuthController {
  /** The cookie's name over plain HTTP; behind HTTPS it is {@link SessionCookie#SECURE_NAME}. */
  public static final String COOKIE = SessionCookie.PLAIN_NAME;

  private final LoginHandler login;
  private final SessionRegistry sessions;
  private final SessionCookie cookie;
  private final OperatorRegistry operators;

  public AuthController(
      LoginHandler login, SessionRegistry sessions, SessionCookie cookie, OperatorRegistry operators) {
    this.login = login;
    this.sessions = sessions;
    this.cookie = cookie;
    this.operators = operators;
  }

  public record LoginRequest(String email, String password, String deviceId) {}

  @PostMapping("/session")
  public ResponseEntity<Map<String, Object>> signIn(
      @RequestBody LoginRequest body, HttpServletRequest request) {
    UUID deviceId = RequestValues.optionalUuid("deviceId", body.deviceId());
    // The client's address, not the proxy's: Tomcat rewrites it from
    // X-Forwarded-For when the peer is a trusted proxy (server.forward-headers-strategy).
    String token =
        login.login(
            body.email(), body.password(), deviceId, request.getRemoteAddr(), cookie.read(request));

    SessionView session =
        sessions
            .resolve(token)
            .orElseThrow(
                () -> new DomainException(ErrorCode.UNAUTHENTICATED, "Session could not be read"));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.issue(token))
        .body(toBody(session, operators.operatorOf(token)));
  }

  @GetMapping("/session")
  public ResponseEntity<Map<String, Object>> current(HttpServletRequest request) {
    String token = cookie.read(request);
    SessionView session =
        sessions
            .resolve(token)
            .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
    return ResponseEntity.ok(toBody(session, operators.operatorOf(token)));
  }

  @PostMapping("/session/end")
  public ResponseEntity<Void> signOut(HttpServletRequest request) {
    String token = cookie.read(request);
    if (token != null) {
      sessions.revoke(token);
    }
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.cleared()).build();
  }

  private static Map<String, Object> toBody(
      SessionView session, Optional<OperatorRegistry.Operator> currentOperator) {
    Map<String, Object> body = new HashMap<>();
    body.put("userId", session.userId().toString());
    body.put("displayName", session.displayName());
    body.put("roles", session.roles());
    body.put("scope", session.scope());
    // The loader working on a shared device, or null while it is locked (R-IAM-25).
    body.put("operator", currentOperator.map(operator -> Map.of(
        "userId", operator.userId().toString(),
        "displayName", operator.displayName(),
        "employeeCode", operator.employeeCode().orElse(""),
        "since", operator.since().toString())).orElse(null));
    return body;
  }
}
