package com.waypoint.dispatch.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.contract.McpAuthorizationView;
import com.waypoint.dispatch.identity.domain.oauth.CodeExchangePolicy;
import com.waypoint.dispatch.identity.domain.oauth.Pkce;
import com.waypoint.dispatch.identity.domain.oauth.RedirectUriPolicy;
import com.waypoint.dispatch.identity.infrastructure.SessionTokens;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.config.McpProperties;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.error.Violation;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * OAuth 2.1 for remote MCP clients (R-IAM-31): client registration and the
 * authorization code grant with PKCE.
 *
 * <p>Two steps, on purpose far apart. The person signs in on our page, in their
 * own browser, and the client receives a one-time code. The client then trades
 * that code, with the PKCE value only it knows, for the read-only session of
 * R-IAM-30. The client never sees the password and the browser never sees the
 * token.
 *
 * <p>Registration is open, because that is how a hosted assistant obtains a
 * client id. It is safe only because a registration is not a credential: it
 * issues no secret, grants no access, and decides only where a user who then
 * signs in may be sent back to.
 *
 * <p>Like sign-in, this is authentication bookkeeping and not a business
 * command: there is no aggregate and no version to guard, so it does not go
 * through the command bus. Every decision is still audited.
 */
@Component
public class McpOAuthHandler {
  /** The only scope there is. A client may ask for it or for nothing. */
  public static final String SCOPE = "waypoint.read";

  /** Clients collect the token at once, so minutes is generous. */
  static final Duration CODE_LIFETIME = Duration.ofMinutes(2);

  /** Registration is open, so this is what bounds the table. */
  static final int REGISTRATIONS_PER_ADDRESS_PER_HOUR = 20;

  private static final int MAX_REDIRECT_URIS = 10;
  private static final int MAX_NAME = 120;
  private static final int MAX_STATE = 2000;
  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  private final Database database;
  private final McpAccessHandler access;
  private final LoginHandler login;
  private final SessionRegistry sessions;
  private final SessionTokens tokens;
  private final AuditLog audit;
  private final ObjectMapper mapper;
  private final Metrics metrics;
  private final Clock clock;
  private final McpProperties properties;

  public McpOAuthHandler(
      Database database,
      McpAccessHandler access,
      LoginHandler login,
      SessionRegistry sessions,
      SessionTokens tokens,
      AuditLog audit,
      ObjectMapper mapper,
      Metrics metrics,
      Clock clock, McpProperties properties) {
    this.database = database;
    this.access = access;
    this.login = login;
    this.sessions = sessions;
    this.tokens = tokens;
    this.audit = audit;
    this.mapper = mapper;
    this.metrics = metrics;
    this.clock = clock;
    this.properties = properties;
  }

  /** A registered client: who it says it is and where it may send people back to. */
  public record Client(UUID clientId, String name, List<String> redirectUris, Instant registeredAt) {}

  /**
   * What a client put in the authorization URL.
   *
   * @param resource the configured MCP endpoint the token is for (RFC 8707)
   */
  public record Request(
      String clientId,
      String redirectUri,
      String responseType,
      String codeChallenge,
      String codeChallengeMethod,
      String state,
      String resource,
      String scope) {}

  /** A session for the client, and how long it can last at most. */
  public record Grant(String accessToken, Duration lifetime) {}

  // ---- registration (RFC 7591) ------------------------------------------------

  public Client register(String requestedName, List<String> redirectUris, String sourceIp) {
    requireRemote();
    if (redirectUris == null || redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
      throw new OAuthProtocolException(
          400, "invalid_redirect_uri", "Between 1 and " + MAX_REDIRECT_URIS + " redirect URIs are required");
    }
    for (String uri : redirectUris) {
      if (!RedirectUriPolicy.registrable(uri)) {
        throw new OAuthProtocolException(
            400,
            "invalid_redirect_uri",
            "A redirect URI must be https, or http on a loopback address, with no fragment");
      }
    }
    Client client =
        new Client(UuidV7.generate(clock.now(), new SecureRandom()), displayName(requestedName), redirectUris.stream().distinct().toList(), clock.now());

    boolean stored =
        database.asModule(
            ModuleRole.IAM,
            null,
            () -> {
              // Counted in the serializable transaction that inserts, so two
              // registrations racing past the limit cannot both commit.
              Map<String, Object> recent =
                  database.queryOne(
                      "SELECT count(*) AS registered FROM iam.oauth_clients"
                          + " WHERE registered_from IS NOT DISTINCT FROM ?::inet AND registered_at > ?",
                      sourceIp,
                      Timestamp.from(client.registeredAt().minus(Duration.ofHours(1))));
              if (((Number) recent.get("registered")).intValue() >= REGISTRATIONS_PER_ADDRESS_PER_HOUR) {
                return false;
              }
              database.update(
                  "INSERT INTO iam.oauth_clients"
                      + " (client_id, client_name, redirect_uris, registered_at, registered_from)"
                      + " VALUES (?, ?, ?::jsonb, ?, ?::inet)",
                  client.clientId(),
                  client.name(),
                  json(client.redirectUris()),
                  Timestamp.from(client.registeredAt()),
                  sourceIp);
              return true;
            });
    if (!stored) {
      metrics.increment("waypoint.mcp.oauth.registration.limited");
      throw new OAuthProtocolException(
          429, "temporarily_unavailable", "Too many client registrations from this address. Try again later.");
    }
    metrics.increment("waypoint.mcp.oauth.registered");
    return client;
  }

  // ---- authorization: the person signs in --------------------------------------

  /**
   * Checks an authorization request before the page asks for a password.
   *
   * <p>A request that fails here is shown as an error and is never redirected:
   * until the client and its redirect URI are known to match, the redirect URI
   * is just an address an attacker chose.
   */
  public McpAuthorizationView describe(Request request) {
    requireRemote();
    Client client = validated(request);
    return new McpAuthorizationView(client.name(), RedirectUriPolicy.displayHost(request.redirectUri()));
  }

  /**
   * Signs the person in and returns where to send their browser: the client's
   * redirect URI carrying a one-time code.
   */
  public String authorize(Request request, String email, String password, String sourceIp) {
    requireRemote();
    Client client = validated(request);

    // Throws on a wrong password or a lockout, after recording the attempt.
    UUID userId = login.verifyMcp(email, password, sourceIp);
    // The same grant the local adapter needs. A denial is audited where it is decided.
    access.requireGrant(Actor.user(userId));

    String code = tokens.newToken();
    Instant now = clock.now();
    database.asModule(
        ModuleRole.IAM,
        userId,
        () ->
            database.update(
                "INSERT INTO iam.oauth_authorization_codes"
                    + " (code_hash, client_id, user_id, redirect_uri, code_challenge, issued_at, expires_at, resource_uri)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                tokens.hash(code),
                client.clientId(),
                userId,
                request.redirectUri(),
                request.codeChallenge(),
                Timestamp.from(now),
                Timestamp.from(now.plus(CODE_LIFETIME)), effectiveResource(request.resource())));
    audit.recordStandalone(
        AuditEntry.allowed(
            userId,
            null,
            McpAccessHandler.CONNECT,
            McpAccessHandler.RESOURCE,
            "approved remote client " + client.clientId()));

    StringBuilder target = new StringBuilder(request.redirectUri());
    target.append(request.redirectUri().contains("?") ? '&' : '?').append("code=").append(encode(code));
    if (request.state() != null && !request.state().isEmpty()) {
      target.append("&state=").append(encode(request.state()));
    }
    return target.toString();
  }

  // ---- token: the client collects --------------------------------------------

  /** Trades a one-time code for the read-only session. Every refusal reads the same to the caller. */
  public Grant exchange(String code, String clientId, String redirectUri, String codeVerifier, String resource) {
    requireRemote();
    String effectiveResource = effectiveResource(resource);
    if (isBlank(code) || isBlank(clientId) || isBlank(redirectUri) || isBlank(codeVerifier)) {
      throw new OAuthProtocolException(
          400, "invalid_request", "code, client_id, redirect_uri and code_verifier are required");
    }
    UUID client = uuidOrNull(clientId);
    Instant now = clock.now();
    String codeHash = tokens.hash(code);

    // The outcome is returned, not thrown: a refusal still has to commit that the
    // code was spent, and a replay has to commit the revocation it causes.
    Exchange outcome =
        database.asModule(
            ModuleRole.IAM,
            null,
            () -> {
              Map<String, Object> row =
                  database.queryOne(
                      "SELECT client_id, user_id, redirect_uri, code_challenge, expires_at, consumed_at,"
                          + " session_key, resource_uri FROM iam.oauth_authorization_codes WHERE code_hash = ? FOR UPDATE",
                      codeHash);
              if (row == null || client == null) {
                return new Exchange(null, null, "unknown");
              }
              UUID userId = (UUID) row.get("user_id");
              Timestamp consumed = (Timestamp) row.get("consumed_at");
              Optional<CodeExchangePolicy.Refusal> refusal =
                  CodeExchangePolicy.refusal(
                      new CodeExchangePolicy.Issued(
                          (UUID) row.get("client_id"),
                          (String) row.get("redirect_uri"),
                          (String) row.get("code_challenge"),
                          ((Timestamp) row.get("expires_at")).toInstant(),
                          consumed == null ? null : consumed.toInstant()),
                      new CodeExchangePolicy.Presented(client, redirectUri, codeVerifier),
                      now);
              if (refusal.isPresent() && refusal.get() == CodeExchangePolicy.Refusal.REPLAYED) {
                // A code presented twice was seen by someone it was not meant for.
                // Whoever used it first loses the session it produced.
                String sessionKey = (String) row.get("session_key");
                if (sessionKey != null) {
                  sessions.revokeByKeyInTransaction(sessionKey, "code_replay");
                }
                return new Exchange(null, userId, "replayed");
              }
              // Spent on any attempt, successful or not: a code is tried once.
              database.update(
                  "UPDATE iam.oauth_authorization_codes SET consumed_at = ? WHERE code_hash = ?",
                  Timestamp.from(now),
                  codeHash);
              if (refusal.isPresent()) {
                return new Exchange(null, userId, refusal.get().name().toLowerCase(Locale.ROOT));
              }
              if (!effectiveResource.equals(row.get("resource_uri")) || !effectiveResource.equals(properties.publicUrl())) {
                return new Exchange(null, userId, "wrong_resource");
              }
              String token = sessions.issue(userId, null, true);
              database.update("UPDATE iam.sessions SET oauth_resource = ?, oauth_client_id = ? WHERE token_hash = ?",
                  effectiveResource, client, sessions.keyOf(token));
              database.update(
                  "UPDATE iam.oauth_authorization_codes SET session_key = ? WHERE code_hash = ?",
                  sessions.keyOf(token),
                  codeHash);
              database.update(
                  "UPDATE iam.oauth_clients SET last_used_at = ? WHERE client_id = ?",
                  Timestamp.from(now),
                  client);
              return new Exchange(token, userId, null);
            });

    if (outcome.token() == null) {
      throw refused(outcome.userId(), outcome.reason());
    }
    // Between sign-in and exchange the account may have been disabled or the
    // grant withdrawn. The session is checked the way every later request is.
    try {
      var session =
          sessions
              .resolveMcp(outcome.token())
              .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Account is not active"));
      access.requireGrant(sessions.actorOf(session));
    } catch (DomainException denied) {
      sessions.revoke(outcome.token());
      throw refused(outcome.userId(), "no_longer_permitted");
    }
    audit.recordStandalone(
        AuditEntry.allowed(
            outcome.userId(),
            null,
            McpAccessHandler.CONNECT,
            McpAccessHandler.RESOURCE,
            "remote read-only connection for client " + client));
    metrics.increment("waypoint.mcp.oauth.token.issued");
    return new Grant(outcome.token(), sessions.absoluteLifetime());
  }

  private record Exchange(String token, UUID userId, String reason) {}

  private OAuthProtocolException refused(UUID userId, String reason) {
    metrics.increment("waypoint.mcp.oauth.code.refused", "reason", reason);
    audit.recordStandalone(
        AuditEntry.denied(
            userId,
            null,
            McpAccessHandler.CONNECT,
            McpAccessHandler.RESOURCE,
            "authorization code refused: " + reason));
    return OAuthProtocolException.invalidGrant();
  }

  // ---- shared ----------------------------------------------------------------

  /** The client and the redirect URI first: nothing else is trusted until they match. */
  private Client validated(Request request) {
    Client client =
        find(request.clientId()).orElseThrow(() -> invalid("client_id", "is not a registered client"));
    if (!RedirectUriPolicy.matches(client.redirectUris(), request.redirectUri())) {
      throw invalid("redirect_uri", "is not registered for this client");
    }
    if (!"code".equals(request.responseType())) {
      throw invalid("response_type", "must be code");
    }
    if (!Pkce.METHOD.equals(request.codeChallengeMethod()) || !Pkce.validChallenge(request.codeChallenge())) {
      throw invalid("code_challenge", "must be an S256 PKCE challenge");
    }
    if (request.state() != null && request.state().length() > MAX_STATE) {
      throw invalid("state", "is too long");
    }
    if (!properties.publicUrl().equals(effectiveResource(request.resource()))) {
      throw invalid("resource", "is not this MCP endpoint");
    }
    if (!scopeAllowed(request.scope())) {
      throw invalid("scope", "only waypoint.read is available");
    }
    return client;
  }

  /**
   * The single resource of this server. Clients that omit the RFC 8707
   * parameter are bound to it, so a generic assistant that sends no resource
   * still receives a token that is valid only here. Anything else must match
   * exactly; there is nowhere else to be valid.
   */
  private String effectiveResource(String resource) {
    return isBlank(resource) ? properties.publicUrl() : resource;
  }

  /** Blank, exactly the read scope, or a list that contains it. The grant is still only the read scope. */
  private static boolean scopeAllowed(String scope) {
    if (isBlank(scope)) {
      return true;
    }
    for (String token : scope.trim().split("\\s+")) {
      if (SCOPE.equals(token)) {
        return true;
      }
    }
    return false;
  }

  private void requireRemote() {
    if (!properties.remoteEnabled()) throw new DomainException(ErrorCode.FORBIDDEN, "Remote MCP is not enabled");
  }

  /** RFC 7009: reveal nothing about unknown tokens; a public client revokes only its own tokens. */
  public void revoke(String token, String clientId) {
    UUID client = uuidOrNull(clientId == null ? "" : clientId);
    if (client == null || isBlank(token)) return;
    UUID actor = database.asModule(ModuleRole.IAM, null, () -> {
      var row = database.queryOne("SELECT user_id FROM iam.sessions WHERE token_hash = ? AND oauth_client_id = ?",
          tokens.hash(token), client);
      if (row == null) return null;
      sessions.revokeByKeyInTransaction(tokens.hash(token), "oauth_revoke");
      return (UUID) row.get("user_id");
    });
    if (actor != null) audit.recordStandalone(AuditEntry.allowed(actor, null, McpAccessHandler.CONNECT,
        McpAccessHandler.RESOURCE, "remote connection revoked for client " + client));
  }

  private Optional<Client> find(String clientId) {
    UUID id = clientId == null ? null : uuidOrNull(clientId);
    if (id == null) {
      return Optional.empty();
    }
    return database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          Map<String, Object> row =
              database.queryOne(
                  "SELECT client_name, redirect_uris::text AS redirect_uris, registered_at"
                      + " FROM iam.oauth_clients WHERE client_id = ?",
                  id);
          if (row == null) {
            return Optional.<Client>empty();
          }
          return Optional.of(
              new Client(
                  id,
                  (String) row.get("client_name"),
                  strings((String) row.get("redirect_uris")),
                  ((Timestamp) row.get("registered_at")).toInstant()));
        });
  }

  /**
   * The name is what the sign-in page shows, and the client chose it. Control
   * characters are dropped and the length is capped; it is still a claim.
   */
  private static String displayName(String requested) {
    StringBuilder out = new StringBuilder();
    if (requested != null) {
      requested.codePoints().filter(c -> !Character.isISOControl(c)).limit(MAX_NAME).forEach(out::appendCodePoint);
    }
    String name = out.toString().trim();
    return name.isEmpty() ? "MCP client" : name;
  }

  private static DomainException invalid(String field, String problem) {
    String message = field + " " + problem;
    return DomainException.withViolations(
        ErrorCode.VALIDATION_FAILED, message, List.of(Violation.onField("R-IAM-31", field, message)));
  }

  private String json(List<String> values) {
    try {
      return mapper.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not write redirect URIs", e);
    }
  }

  private List<String> strings(String json) {
    try {
      return List.copyOf(mapper.readValue(json, STRINGS));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored redirect URIs are not a JSON array", e);
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static UUID uuidOrNull(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
