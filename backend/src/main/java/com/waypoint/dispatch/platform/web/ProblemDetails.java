package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.shared.error.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 9457 problem details. The error body is part of the API contract, because
 * clients branch on it, so it is a typed record rather than an ad hoc map.
 *
 * @param code the stable {@code ErrorCode} name clients branch on. The title is for people
 *     and may be reworded; the code may not.
 * @param correlationId the id of the request that failed, also on every log line and audit
 *     row it produced, so a support call can quote it
 * @param violations each failed constraint as {@code {rule, field?, message}}
 */
public record ProblemDetails(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    String correlationId,
    List<Violation> violations) {

  public Map<String, Object> toBody() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("title", title);
    body.put("status", status);
    body.put("detail", detail == null ? "" : detail);
    body.put("instance", instance == null ? "" : instance);
    body.put("code", code);
    body.put("correlationId", correlationId == null ? "" : correlationId);
    body.put("violations", violations.stream().map(ProblemDetails::violationBody).toList());
    return body;
  }

  private static Map<String, Object> violationBody(Violation violation) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rule", violation.rule());
    if (violation.field() != null) {
      body.put("field", violation.field());
    }
    body.put("message", violation.message());
    return body;
  }
}
