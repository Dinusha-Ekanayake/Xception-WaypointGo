package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.McpAccessHandler;
import com.waypoint.dispatch.identity.application.McpConnectionsQuery;
import com.waypoint.dispatch.identity.application.McpConnectionsQuery.ClientView;
import com.waypoint.dispatch.identity.application.McpConnectionsQuery.ConnectionView;
import com.waypoint.dispatch.identity.application.McpConnectionsQuery.PrincipalAccess;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser views of MCP access (issue #177). An MCP credential cannot reach
 * these: they are outside its route boundary, so an assistant can neither list
 * nor end anyone's connections. Blocking a client is a command
 * ({@code mcp:BlockClient}) through {@code /api/commands}.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpConnectionsController {
  public static final String MANAGE_CLIENTS = "mcp:ManageClients";
  private final McpConnectionsQuery connections;
  private final RequestAuthorizer authorizer;

  public McpConnectionsController(McpConnectionsQuery connections, RequestAuthorizer authorizer) {
    this.connections = connections;
    this.authorizer = authorizer;
  }

  public record Revoke(String connectionId) {}

  /** Your own connected assistants. */
  @GetMapping("/connections")
  public List<ConnectionView> own(HttpServletRequest request) {
    return connections.own(authorizer.require(request, McpAccessHandler.CONNECT, McpAccessHandler.RESOURCE));
  }

  @PostMapping("/connections/revoke")
  public ResponseEntity<Void> revoke(@RequestBody Revoke body, HttpServletRequest request) {
    var actor = authorizer.require(request, McpAccessHandler.CONNECT, McpAccessHandler.RESOURCE);
    if (body == null || body.connectionId() == null || !body.connectionId().matches("[0-9A-Za-z_-]{16,128}")) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "connectionId is not a connection id");
    }
    connections.revokeOwn(actor, body.connectionId());
    return ResponseEntity.noContent().build();
  }

  /** Every registered client, for an administrator deciding what to block. */
  @GetMapping("/clients")
  public List<ClientView> clients(HttpServletRequest request) {
    return connections.clients(authorizer.require(request, MANAGE_CLIENTS, "wpt:mcp:client:*"));
  }

  /** Every role with the MCP switches attached to it (R-IAM-38). */
  @GetMapping("/access/roles")
  public List<PrincipalAccess> roles(HttpServletRequest request) {
    return connections.roles(authorizer.require(request, MANAGE_CLIENTS, "wpt:mcp:client:*"));
  }

  /** People with a switch or a live connection, or those matching {@code q} (R-IAM-38). */
  @GetMapping("/access/people")
  public Page<PrincipalAccess> people(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    if (q != null && q.length() > 100) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "q must be at most 100 characters");
    }
    return connections.people(authorizer.require(request, MANAGE_CLIENTS, "wpt:mcp:client:*"), q, after, limit);
  }
}
