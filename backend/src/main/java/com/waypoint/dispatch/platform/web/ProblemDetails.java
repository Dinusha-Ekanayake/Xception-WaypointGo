package com.waypoint.dispatch.platform.web;

import java.util.List;
import java.util.Map;

/**
 * RFC 9457 problem details. The error body is part of the API contract, because
 * clients branch on it, so it is a typed record rather than an ad hoc map.
 */
public record ProblemDetails(
    String type, String title, int status, String detail, String instance, List<String> violations) {

  public Map<String, Object> toBody() {
    return Map.of(
        "type", type,
        "title", title,
        "status", status,
        "detail", detail == null ? "" : detail,
        "instance", instance == null ? "" : instance,
        "violations", violations);
  }
}
