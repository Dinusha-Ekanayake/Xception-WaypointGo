package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.identity.infrastructure.SessionTokens;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Server side sessions.
 *
 * <p>Named a registry rather than a service: the boundary test forbids
 * {@code *Service}, which is the name that let one class absorb eight
 * responsibilities in the version of this system that was deleted.
 *
 * <p>Opaque tokens, not self contained ones. Revocation has to be immediate:
 * disabling an account, or changing a policy, must take effect on the next
 * request rather than whenever a token happens to expire. A self contained token
 * cannot do that without a server side check, at which point it is a session
 * with extra moving parts.
 *
 * <p>Two expiries, because they answer different questions. Absolute caps how
 * long one sign-in can last; idle ends a session nobody is using. A driver's
 * phone left in a cab overnight should not stay signed in.
 *
 * <p>Only a hash of the token is stored, and both expiries are compared with the
 * injected clock, so a test can move time and the database's clock is not a
 * second opinion on when a session ends.
 */
@Component
public class SessionRegistry {
  private final Database database;
  private final SessionTokens tokens;
  private final OperatorRegistry operators;
  private final Clock clock;
  private final Metrics metrics;

  private final Duration absoluteLifetime;
  private final Duration idleLifetime;
  private final Duration touchInterval;

  public SessionRegistry(
      Database database,
      SessionTokens tokens,
      OperatorRegistry operators,
      Clock clock,
      AppProperties properties,
      Metrics metrics) {
    this.database = database;
    this.tokens = tokens;
    this.operators = operators;
    this.clock = clock;
    this.metrics = metrics;
    this.absoluteLifetime = properties.session().absoluteLifetime();
    this.idleLifetime = properties.session().idleLifetime();
    this.touchInterval = properties.session().touchInterval();
  }

  /** How long a sign-in can last, which is also how long its cookie is kept. */
  public Duration absoluteLifetime() {
    return absoluteLifetime;
  }

  /** Runs in the caller's transaction, so a session never outlives a sign-in that rolled back. */
  public String issue(UUID userId, UUID deviceId) {
    return issue(userId, deviceId, false);
  }

  /** A purpose is fixed when authentication issues the credential, never upgraded later. */
  public String issue(UUID userId, UUID deviceId, boolean mcpReadOnly) {
    String token = (mcpReadOnly ? "mcp." : "") + tokens.newToken();
    Instant now = clock.now();
    database.update(
        """
        INSERT INTO iam.sessions
            (token_hash, user_id, device_id, issued_at, last_seen_at,
             absolute_expiry, idle_expiry, mcp_read_only)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        tokens.hash(token),
        userId,
        deviceId,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now.plus(absoluteLifetime)),
        Timestamp.from(now.plus(idleLifetime)),
        mcpReadOnly);
    return token;
  }

  /** Resolves a token, sliding the idle expiry. Returns empty for expired or unknown tokens. */
  public Optional<SessionView> resolve(String token) {
    return resolve(token, false);
  }

  public Optional<SessionView> resolveMcp(String token) {
    return token != null && token.startsWith("mcp.") ? resolve(token, true) : Optional.empty();
  }

  private Optional<SessionView> resolve(String token, boolean mcpReadOnly) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    String hash = tokens.hash(token);
    return database.<Optional<SessionView>>asModule(
        ModuleRole.IAM,
        null,
        () -> {
          Instant now = clock.now();
          Map<String, Object> row =
              database.queryOne(
                  """
                  SELECT s.user_id, s.device_id, s.last_seen_at, u.display_name, u.is_active
                  FROM iam.sessions s
                  JOIN iam.users u ON u.user_id = s.user_id
                  WHERE s.token_hash = ?
                    AND s.mcp_read_only = ?
                    AND s.absolute_expiry > ?
                    AND s.idle_expiry > ?
                  """,
                  hash,
                  mcpReadOnly,
                  Timestamp.from(now),
                  Timestamp.from(now));
          if (row == null || !Boolean.TRUE.equals(row.get("is_active"))) {
            return Optional.<SessionView>empty();
          }
          UUID userId = (UUID) row.get("user_id");
          // Every authenticated request reads the session. Writing it back each
          // time made every read a write; once a minute keeps the idle expiry
          // honest to within that minute.
          Instant lastSeen = ((Timestamp) row.get("last_seen_at")).toInstant();
          if (!now.isBefore(lastSeen.plus(touchInterval))) {
            database.update(
                "UPDATE iam.sessions SET last_seen_at = ?, idle_expiry = ? WHERE token_hash = ?",
                Timestamp.from(now),
                Timestamp.from(now.plus(idleLifetime)),
                hash);
          }
          List<String> roles =
              database.query("SELECT role_code FROM iam.user_roles WHERE user_id = ?", userId)
                  .stream()
                  .map(r -> (String) r.get("role_code"))
                  .toList();
          return Optional.of(
              new SessionView(
                  userId,
                  (String) row.get("display_name"),
                  roles,
                  scopeOf(userId),
                  (UUID) row.get("device_id")));
        });
  }

  /** Depot codes and outlet ids this actor may see. The coarse boundary RLS also enforces. */
  private List<String> scopeOf(UUID userId) {
    List<String> scope = new java.util.ArrayList<>();
    database
        .query("SELECT depot_code FROM iam.user_depot_access WHERE user_id = ?", userId)
        .forEach(r -> scope.add("depot:" + r.get("depot_code")));
    database
        .query("SELECT outlet_id FROM iam.user_outlet_access WHERE user_id = ?", userId)
        .forEach(r -> scope.add("outlet:" + r.get("outlet_id")));
    return List.copyOf(scope);
  }

  /** Remote credentials are accepted only for their issued resource; local credentials have no audience. */
  public boolean matchesMcpResource(String token, String resource) {
    return database.readAs(ModuleRole.IAM, null, () -> {
      var row = database.queryOne("SELECT oauth_resource FROM iam.sessions WHERE token_hash = ? AND mcp_read_only", tokens.hash(token));
      return row != null && java.util.Objects.equals(row.get("oauth_resource"), resource);
    });
  }

  /** Sign-out: one session, in a transaction of its own. */
  public void revoke(String token) {
    database.asModule(ModuleRole.IAM, null, () -> revokeInTransaction(token));
  }

  /** One session, inside the caller's transaction. */
  public void revokeInTransaction(String token) {
    revokeByKeyInTransaction(tokens.hash(token), "replaced");
  }

  /**
   * One session named by the hash that is stored, for a caller that never held
   * the token itself: a replayed authorization code revokes what its first use
   * created (SEC-35). Inside the caller's transaction.
   *
   * @param reason why, as a metric tag
   */
  public void revokeByKeyInTransaction(String sessionKey, String reason) {
    // A shared loader device's operator history ends with its session.
    operators.endForSessionsInTransaction(
        "SELECT token_hash FROM iam.sessions WHERE token_hash = ?", sessionKey, "sign_out");
    int revoked = database.update("DELETE FROM iam.sessions WHERE token_hash = ?", sessionKey);
    count(revoked, reason);
  }

  /** The stored form of a token, for a caller that must remember which session it issued. */
  public String keyOf(String token) {
    return tokens.hash(token);
  }

  /**
   * Every session for one account, used when it is disabled or its access
   * changes. Runs in the caller's transaction so revocation commits with the
   * change that caused it rather than a moment later.
   *
   * @param reason why, as a metric tag: {@code disabled}, {@code password}, {@code role} or
   *     {@code scope}
   */
  public int revokeAllFor(UUID userId, String reason) {
    operators.endForSessionsInTransaction(
        "SELECT token_hash FROM iam.sessions WHERE user_id = ?", userId, "revoked");
    int revoked = database.update("DELETE FROM iam.sessions WHERE user_id = ?", userId);
    count(revoked, reason);
    return revoked;
  }

  /** Every session opened from one device, when it is retired. In the caller's transaction. */
  public int revokeAllOn(UUID deviceId) {
    operators.endForSessionsInTransaction(
        "SELECT token_hash FROM iam.sessions WHERE device_id = ?", deviceId, "revoked");
    int revoked = database.update("DELETE FROM iam.sessions WHERE device_id = ?", deviceId);
    count(revoked, "device");
    return revoked;
  }

  public Actor actorOf(SessionView session) {
    return new Actor(session.userId(), session.deviceId());
  }

  /** SEC-02: counted once the change that revoked them has committed, not if it rolls back. */
  private void count(int revoked, String reason) {
    if (revoked > 0) {
      database.afterCommit(
          () -> metrics.count("waypoint.session.revoked", revoked, "reason", reason));
    }
  }
}
