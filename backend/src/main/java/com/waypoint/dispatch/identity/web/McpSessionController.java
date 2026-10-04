package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.McpContextQuery;
import com.waypoint.dispatch.identity.application.McpSessionHandler;
import com.waypoint.dispatch.identity.contract.McpContextView;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Local-client authentication, not an MCP HTTP transport or an OAuth endpoint. */
@RestController
@RequestMapping("/api/mcp")
public class McpSessionController {
  private final McpSessionHandler sessions;
  private final McpContextQuery context;

  public McpSessionController(McpSessionHandler sessions, McpContextQuery context) {
    this.sessions = sessions;
    this.context = context;
  }

  /** @param scope optional, space separated (R-IAM-34); blank is the default grant */
  public record Credentials(String email, String password, String scope) {}

  @PostMapping("/session")
  public ResponseEntity<Map<String, String>> connect(@RequestBody Credentials body, HttpServletRequest request) {
    String token = sessions.connect(body.email(), body.password(), request.getRemoteAddr(), body.scope());
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("token", token));
  }

  @PostMapping("/session/end")
  public ResponseEntity<Void> disconnect(HttpServletRequest request) {
    sessions.disconnect(credential(request));
    return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
  }

  @GetMapping("/context")
  public ResponseEntity<McpContextView> context(HttpServletRequest request) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(context.context(credential(request)));
  }

  private static String credential(HttpServletRequest request) {
    String token = (String) request.getAttribute(McpCredentialFilter.CREDENTIAL);
    if (token == null) {
      throw new DomainException(ErrorCode.UNAUTHENTICATED, "Use a dedicated MCP bearer credential");
    }
    return token;
  }
}
