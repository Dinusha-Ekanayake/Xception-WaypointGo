package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.web.RequestValues;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;
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
  public static final String COOKIE = "waypoint_session";

  private final LoginHandler login;
  private final SessionRegistry sessions;
  private final AppProperties properties;
  private final OperatorRegistry operators;

  public AuthController(
      LoginHandler login, SessionRegistry sessions, AppProperties properties, OperatorRegistry operators) {
    this.login = login;
    this.sessions = sessions;
    this.properties = properties;
    this.operators = operators;
  }

  public record LoginRequest(String email, String password, String deviceId) {}

  @PostMapping("/session")
  public ResponseEntity<Map<String, Object>> signIn(
      @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
    UUID deviceId = RequestValues.optionalUuid("deviceId", body.deviceId());
    String token = login.login(body.email(), body.password(), deviceId, request.getRemoteAddr());

    Cookie cookie = new Cookie(COOKIE, token);
    cookie.setHttpOnly(true);
    cookie.setSecure(properties.cookieSecure());
    cookie.setPath("/");
    cookie.setAttribute("SameSite", "Strict");
    response.addCookie(cookie);

    SessionView session =
        sessions
            .resolve(token)
            .orElseThrow(
                () -> new DomainException(ErrorCode.UNAUTHENTICATED, "Session could not be read"));
    return ResponseEntity.ok(toBody(session, operators.operatorOf(token)));
  }

  @GetMapping("/session")
  public ResponseEntity<Map<String, Object>> current(HttpServletRequest request) {
    SessionView session =
        sessions
            .resolve(tokenFrom(request))
            .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
    return ResponseEntity.ok(toBody(session, operators.operatorOf(tokenFrom(request))));
  }

  @PostMapping("/session/end")
  public ResponseEntity<Void> signOut(HttpServletRequest request, HttpServletResponse response) {
    String token = tokenFrom(request);
    if (token != null) {
      sessions.revoke(token);
    }
    Cookie cleared = new Cookie(COOKIE, "");
    cleared.setHttpOnly(true);
    cleared.setSecure(properties.cookieSecure());
    cleared.setPath("/");
    cleared.setMaxAge(0);
    response.addCookie(cleared);
    return ResponseEntity.noContent().build();
  }

  private static Map<String, Object> toBody(
      SessionView session, java.util.Optional<OperatorRegistry.Operator> currentOperator) {
    Map<String, Object> body = new HashMap<>();
    body.put("userId", session.userId().toString());
    body.put("displayName", session.displayName());
    body.put("roles", session.roles());
    body.put("scope", session.scope());
    body.put("operator", currentOperator.map(operator -> Map.of(
        "userId", operator.userId().toString(),
        "displayName", operator.displayName(),
        "employeeCode", operator.employeeCode().orElse(""),
        "since", operator.since().toString())).orElse(null));
    return body;
  }

  static String tokenFrom(HttpServletRequest request) {
    if (request.getCookies() == null) {
      return null;
    }
    for (Cookie cookie : request.getCookies()) {
      if (COOKIE.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
