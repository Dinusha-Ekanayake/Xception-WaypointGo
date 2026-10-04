package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.OperatorRegistry;
import com.waypoint.dispatch.identity.application.SessionRegistry;
import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import com.waypoint.dispatch.identity.domain.OfflineSwitchPolicy;
import com.waypoint.dispatch.identity.domain.OfflineSwitchPolicy.Switch;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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
  private final Clock clock;
  private final SessionCookie cookie;

  public OperatorController(
      SessionRegistry sessions, OperatorRegistry operators, Clock clock, SessionCookie cookie) {
    this.sessions = sessions;
    this.operators = operators;
    this.clock = clock.realTime();
    this.cookie = cookie;
  }

  public record SwitchRequest(String userId, String pin) {}

  public record OfflineSwitch(String userId, String at) {}

  public record OfflineRequest(List<OfflineSwitch> switches) {}

  /**
   * The crew a loader may switch to on this device, with the PIN check each one
   * may use offline until {@code expiresAt} (R-IAM-27). Never the PIN itself.
   */
  @GetMapping("/crew")
  public Map<String, Object> crew(HttpServletRequest request) {
    String token = cookie.read(request);
    SessionView device = signedIn(token);
    List<Map<String, Object>> members = operators.crew(device).stream().map(member -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("userId", member.userId().toString());
      m.put("displayName", member.displayName());
      m.put("employeeCode", member.employeeCode() == null ? "" : member.employeeCode());
      m.put("offlineVerifier", member.offlineVerifier().orElse(null));
      return m;
    }).toList();
    return Map.of(
        "members", members,
        "expiresAt", clock.now().plus(OfflineSwitchPolicy.CREW_LIST_LIFETIME).toString());
  }

  /** Switches made while offline, sent on reconnect before the queued work they cover. */
  @PostMapping("/operator/offline")
  public Map<String, Object> replayOffline(@RequestBody OfflineRequest body, HttpServletRequest request) {
    String token = cookie.read(request);
    SessionView device = signedIn(token);
    if (body == null || body.switches() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "switches are required");
    }
    List<Switch> switches;
    try {
      switches = body.switches().stream().map(s -> new Switch(
          s.userId() == null ? Optional.<UUID>empty() : Optional.of(UUID.fromString(s.userId())),
          Instant.parse(s.at()))).toList();
    } catch (RuntimeException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Each switch needs a uuid or null userId and an instant");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operator", operators.replayOffline(token, device, switches).map(operator -> Map.of(
        "userId", operator.userId().toString(),
        "displayName", operator.displayName(),
        "employeeCode", operator.employeeCode().orElse(""),
        "since", operator.since().toString())).orElse(null));
    return result;
  }

  @PostMapping("/operator")
  public ResponseEntity<?> switchOperator(@RequestBody SwitchRequest body, HttpServletRequest request) {
    String token = cookie.read(request);
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
    String token = cookie.read(request);
    signedIn(token);
    operators.end(token, "lock");
    return ResponseEntity.noContent().build();
  }

  private SessionView signedIn(String token) {
    return sessions.resolve(token)
        .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));
  }
}
