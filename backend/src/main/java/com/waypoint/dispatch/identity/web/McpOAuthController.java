package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.domain.McpScopes;

import com.waypoint.dispatch.identity.application.McpOAuthHandler;
import com.waypoint.dispatch.identity.application.OAuthProtocolException;
import com.waypoint.dispatch.platform.config.McpProperties;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The OAuth 2.1 endpoints a remote MCP client calls (R-IAM-31): the two
 * discovery documents, client registration and the token exchange.
 *
 * <p>Failures here are {@code {"error": ...}} as RFC 6749 and RFC 7591 define
 * them, not problem+json. The callers are third-party OAuth libraries that
 * branch on that field, so this is the one place the API's error contract gives
 * way to someone else's.
 *
 * <p>Thin on purpose: it moves values between HTTP and the application layer and
 * decides nothing.
 */
@RestController
@RequestMapping("/api/oauth")
public class McpOAuthController {
  private final McpOAuthHandler oauth;
  private final McpProperties properties;

  public McpOAuthController(McpOAuthHandler oauth, McpProperties properties) {
    this.oauth = oauth;
    this.properties = properties;
  }

  public record Registration(
      List<String> redirect_uris,
      String client_name,
      String token_endpoint_auth_method,
      List<String> grant_types,
      List<String> response_types) {}

  /** RFC 9728: which authorization server guards the MCP endpoint at this address. */
  @GetMapping("/protected-resource")
  public ResponseEntity<Map<String, Object>> protectedResource(HttpServletRequest request) {
    requireDiscoverable();
    String origin = properties.publicOrigin();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("resource", origin + McpAuthorizationController.MCP_PATH);
    body.put("authorization_servers", List.of(origin));
    body.put("bearer_methods_supported", List.of("header"));
    body.put("scopes_supported", McpScopes.supported());
    body.put("resource_name", "Waypoint Dispatch (read-only)");
    return json(200, body);
  }

  /** RFC 8414: where to register, send the user, and collect the token. */
  @GetMapping("/authorization-server")
  public ResponseEntity<Map<String, Object>> authorizationServer(HttpServletRequest request) {
    requireDiscoverable();
    String origin = properties.publicOrigin();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("issuer", origin);
    // A page, served by the frontend. Everything else is this controller.
    body.put("authorization_endpoint", origin + "/oauth/authorize");
    body.put("token_endpoint", origin + "/api/oauth/token");
    body.put("registration_endpoint", origin + "/api/oauth/register");
    body.put("revocation_endpoint", origin + "/api/oauth/revoke");
    body.put("revocation_endpoint_auth_methods_supported", List.of("none"));
    body.put("response_types_supported", List.of("code"));
    body.put("grant_types_supported", List.of("authorization_code"));
    body.put("code_challenge_methods_supported", List.of("S256"));
    body.put("token_endpoint_auth_methods_supported", List.of("none"));
    body.put("scopes_supported", McpScopes.supported());
    return json(200, body);
  }

  @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> register(
      @RequestBody Registration body, HttpServletRequest request) {
    if (body.token_endpoint_auth_method() != null && !"none".equals(body.token_endpoint_auth_method())) {
      throw new OAuthProtocolException(
          400, "invalid_client_metadata", "Only public clients are supported: token_endpoint_auth_method must be none");
    }
    if (body.grant_types() != null && !body.grant_types().contains("authorization_code")) {
      throw new OAuthProtocolException(
          400, "invalid_client_metadata", "grant_types must include authorization_code");
    }
    if (body.response_types() != null && !body.response_types().contains("code")) {
      throw new OAuthProtocolException(400, "invalid_client_metadata", "response_types must include code");
    }
    McpOAuthHandler.Client client =
        oauth.register(body.client_name(), body.redirect_uris(), request.getRemoteAddr());
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("client_id", client.clientId().toString());
    answer.put("client_id_issued_at", client.registeredAt().getEpochSecond());
    answer.put("client_name", client.name());
    answer.put("redirect_uris", client.redirectUris());
    // What was granted, which may be less than what was asked for: no refresh
    // tokens and no client secret exist.
    answer.put("token_endpoint_auth_method", "none");
    answer.put("grant_types", List.of("authorization_code"));
    answer.put("response_types", List.of("code"));
    return json(201, answer);
  }

  @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<Map<String, Object>> token(
      @RequestParam(name = "grant_type", required = false) String grantType,
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "client_id", required = false) String clientId,
      @RequestParam(name = "redirect_uri", required = false) String redirectUri,
      @RequestParam(name = "code_verifier", required = false) String codeVerifier,
      @RequestParam(name = "resource", required = false) String resource) {
    if (!"authorization_code".equals(grantType)) {
      throw new OAuthProtocolException(
          400, "unsupported_grant_type", "Only the authorization_code grant is supported");
    }
    McpOAuthHandler.Grant grant = oauth.exchange(code, clientId, redirectUri, codeVerifier, resource);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("access_token", grant.accessToken());
    body.put("token_type", "Bearer");
    body.put("expires_in", grant.lifetime().toSeconds());
    body.put("scope", McpScopes.format(grant.scopes()));
    return json(200, body);
  }

  @PostMapping(path = "/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  public ResponseEntity<Map<String, String>> revoke(
      @RequestParam(name = "token", required = false) String token,
      @RequestParam(name = "client_id", required = false) String clientId) {
    oauth.revoke(token, clientId);
    return json(200, Map.of());
  }

  /** Switched off, the documents do not exist, so a client finds nothing to authorize against. */
  private void requireDiscoverable() {
    if (!properties.remoteEnabled()) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No such endpoint");
    }
  }

  private static <T> ResponseEntity<Map<String, T>> json(int status, Map<String, T> body) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body);
  }

  @ExceptionHandler(OAuthProtocolException.class)
  ResponseEntity<Map<String, String>> onProtocol(OAuthProtocolException e) {
    return error(e.status(), e.error(), e.getMessage());
  }

  /** The feature switch, a lockout and the like, said the way an OAuth client reads it. */
  @ExceptionHandler(DomainException.class)
  ResponseEntity<Map<String, String>> onDomain(DomainException e) {
    return switch (e.code()) {
      case NOT_FOUND -> error(404, "not_found", "No such endpoint");
      case FORBIDDEN -> error(403, "access_denied", e.getMessage());
      case RATE_LIMITED -> error(429, "temporarily_unavailable", e.getMessage());
      default -> error(400, "invalid_request", e.getMessage());
    };
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, String>> onUnreadable(HttpMessageNotReadableException e) {
    return error(400, "invalid_client_metadata", "The request body is not valid JSON for this endpoint");
  }

  private static ResponseEntity<Map<String, String>> error(int status, String error, String description) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("error", error);
    body.put("error_description", description);
    return json(status, body);
  }
}
