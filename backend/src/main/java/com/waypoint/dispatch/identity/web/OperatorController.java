package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** PIN switching for the shared loader device. */
@RestController
@RequestMapping("/api/session")
public class OperatorController {
  private final SessionRegistry sessions;
  private final OperatorRegistry operators;

  public OperatorController(SessionRegistry sessions, OperatorRegistry operators) {
    this.sessions = sessions;
    this.operators = operators;
  }

  public record SwitchRequest(String userId, String pin) {}

  @GetMapping("/crew")
  public List<Map<String, String>> crew(HttpServletRequest request) {
    String token = AuthController.tokenFrom(request);
    SessionView device = signedIn(token);
    return operators.crew(device).stream().map(member -> Map.of(
        "userId", member.userId().toString(),
        "displayName", member.displayName(),
        "employeeCode", member.employeeCode() == null ? "" : member.employeeCode())).toList();
  }

  @PostMapping("/operator")
  public ResponseEntity<?> switchOperator(@RequestBody SwitchRequest body, HttpServletRequest request) {
    String token = AuthController.tokenFrom(request);
    SessionView device = signedIn(token);
    UUID userId;
    try {
      userId = UUID.fromString(body.userId());
    } catch (RuntimeException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "userId must be a uuid");
    }
    OperatorRegistry.SwitchResult result = operators.switchTo(token, device, userId, body.pin());
    if (result instanceof OperatorRegistry.Switched switched) {
      var operator = switched.operator();
      return ResponseEntity.ok(Map.of(
          "userId", operator.userId().toString(),
          "displayName", operator.displayName(),
          "employeeCode", operator.employeeCode().orElse(""),
          "since", operator.since().toString()));
    }
    if (result instanceof OperatorRegistry.Paused paused) {
      long seconds = Math.max(1, (paused.remaining().toMillis() + 999) / 1000);
      return ResponseEntity.status(429)
          .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
          .body(Map.of("triesLeft", 0, "retryAfterSeconds", seconds));
    }
    int triesLeft = ((OperatorRegistry.WrongPin) result).triesLeft();
    return ResponseEntity.status(401).body(Map.of("triesLeft", triesLeft));
  }

  @DeleteMapping("/operator")
  public ResponseEntity<Void> lock(HttpServletRequest request) {
    String token = AuthController.tokenFrom(request);
    signedIn(token);
    operators.end(token, "lock");
    return ResponseEntity.noContent().build();
  }

  private SessionView signedIn(String token) {
    return sessions.resolve(token)
        .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
  }
}
