package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.McpOAuthHandler;
import com.waypoint.dispatch.identity.contract.McpAuthorizationView;
import com.waypoint.dispatch.platform.web.RequestOrigin;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the sign-in page for a remote MCP client calls (R-IAM-31).
 *
 * <p>These two are ours: the page is ours, so failures are problem+json like
 * every other screen's. The endpoints an OAuth client calls are in
 * {@link McpOAuthController}.
 */
@RestController
@RequestMapping("/api/oauth/authorize")
public class McpAuthorizationController {
  /** The path of the MCP endpoint under the public origin. */
  static final String MCP_PATH = "/mcp";

  private final McpOAuthHandler oauth;

  public McpAuthorizationController(McpOAuthHandler oauth) {
    this.oauth = oauth;
  }

  public record Approval(
      String clientId,
      String redirectUri,
      String responseType,
      String codeChallenge,
      String codeChallengeMethod,
      String state,
      String resource,
      String email,
      String password) {}

  /** Who is asking and where the person would be sent, before any password is typed. */
  @GetMapping
  public ResponseEntity<McpAuthorizationView> describe(
      @RequestParam(name = "client_id", required = false) String clientId,
      @RequestParam(name = "redirect_uri", required = false) String redirectUri,
      @RequestParam(name = "response_type", required = false) String responseType,
      @RequestParam(name = "code_challenge", required = false) String codeChallenge,
      @RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "resource", required = false) String resource,
      HttpServletRequest request) {
    McpAuthorizationView view =
        oauth.describe(
            new McpOAuthHandler.Request(
                clientId, redirectUri, responseType, codeChallenge, codeChallengeMethod, state, resource,
                originOf(request) + MCP_PATH));
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(view);
  }

  /** Signs in and answers with the address to send the browser to. Never a token. */
  @PostMapping
  public ResponseEntity<Map<String, String>> approve(
      @RequestBody Approval body, HttpServletRequest request) {
    String redirectTo =
        oauth.authorize(
            new McpOAuthHandler.Request(
                body.clientId(),
                body.redirectUri(),
                body.responseType(),
                body.codeChallenge(),
                body.codeChallengeMethod(),
                body.state(),
                body.resource(),
                originOf(request) + MCP_PATH),
            body.email(),
            body.password(),
            request.getRemoteAddr());
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("redirectTo", redirectTo));
  }

  static String originOf(HttpServletRequest request) {
    String origin = RequestOrigin.of(request);
    if (origin == null) {
      throw new DomainException(ErrorCode.BAD_REQUEST, "The request names no host");
    }
    return origin;
  }
}
