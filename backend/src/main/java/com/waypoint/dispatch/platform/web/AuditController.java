package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.platform.audit.AuditQuery;
import com.waypoint.dispatch.platform.audit.AuditQuery.AuditRowView;
import com.waypoint.dispatch.platform.audit.AuditQuery.DecisionReplay;
import com.waypoint.dispatch.platform.audit.AuditQuery.Filter;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit log, for auditors and administrators. Read only: nothing here writes,
 * and nothing anywhere updates or deletes an audit row.
 *
 * <p>Policy decides {@code audit:Read}. A caller without it gets {@code 403} and
 * an audit row of their own attempt, never an empty page: an access problem must
 * not look like an empty log.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {
  private final AuditQuery audit;
  private final RequestAuthorizer authorizer;

  public AuditController(AuditQuery audit, RequestAuthorizer authorizer) {
    this.audit = audit;
    this.authorizer = authorizer;
  }

  /** Filters combine with AND. Time bounds are ISO-8601 instants; {@code to} is exclusive. */
  @GetMapping
  public Page<AuditRowView> list(
      @RequestParam(required = false) UUID actor,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String targetId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) String correlationId,
      @RequestParam(required = false) UUID commandId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    var caller = authorizer.require(request, AuditQuery.READ, "wpt:platform:audit:*");
    if (decision != null && !decision.isBlank() && !decision.equals("ALLOW") && !decision.equals("DENY")) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "decision must be ALLOW or DENY");
    }
    Filter filter =
        new Filter(
            actor,
            targetType,
            targetId,
            action,
            decision,
            correlationId,
            commandId,
            instant(from, "from"),
            instant(to, "to"));
    return audit.list(caller, filter, Optional.ofNullable(cursor), limit);
  }

  /** Why a command was allowed or refused, as recorded at the time (POL-03). */
  @GetMapping("/decisions/{commandId}")
  public DecisionReplay decision(@PathVariable UUID commandId, HttpServletRequest request) {
    var caller = authorizer.require(request, AuditQuery.READ, "wpt:platform:audit:" + commandId);
    return audit.decision(caller, commandId);
  }

  private static Instant instant(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, field + " must be an ISO-8601 instant such as 2026-10-01T00:00:00Z");
    }
  }
}
