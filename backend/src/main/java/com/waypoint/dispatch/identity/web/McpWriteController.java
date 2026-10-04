package com.waypoint.dispatch.identity.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.identity.application.McpWriteHandler;
import com.waypoint.dispatch.platform.web.CorrelationIdFilter;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two MCP write steps (R-IAM-35). Reachable only with a dedicated MCP
 * credential; a browser session has {@code /api/commands} and needs neither.
 */
@RestController
@RequestMapping("/api/mcp/writes")
public class McpWriteController {
  private final McpWriteHandler writes;

  public McpWriteController(McpWriteHandler writes) {
    this.writes = writes;
  }

  public record PrepareRequest(String tool, Long expectedVersion, JsonNode payload) {}

  public record ConfirmRequest(String confirmation) {}

  @PostMapping
  public ResponseEntity<McpWriteHandler.Prepared> prepare(@RequestBody PrepareRequest body, HttpServletRequest request) {
    if (body == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "A write request is required");
    }
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(writes.prepare(credential(request), body.tool(), body.expectedVersion(), body.payload()));
  }

  @PostMapping("/confirm")
  public ResponseEntity<McpWriteHandler.Confirmed> confirm(@RequestBody ConfirmRequest body, HttpServletRequest request) {
    if (body == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "A confirmation is required");
    }
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(writes.confirm(credential(request), body.confirmation(),
            (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE)));
  }

  private static String credential(HttpServletRequest request) {
    String token = (String) request.getAttribute(McpCredentialFilter.CREDENTIAL);
    if (token == null) {
      throw new DomainException(ErrorCode.UNAUTHENTICATED, "Use a dedicated MCP bearer credential");
    }
    return token;
  }
}
