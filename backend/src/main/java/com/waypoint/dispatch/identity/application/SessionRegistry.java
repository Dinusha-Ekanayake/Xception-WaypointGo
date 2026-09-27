package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.identity.infrastructure.PolicyCache;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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
 */
@Component
public class SessionRegistry {
  private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(12);
  private static final Duration IDLE_LIFETIME = Duration.ofHours(2);
  private static final int TOKEN_BYTES = 32;

  private final Database database;
  private final PolicyCache policyCache;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public SessionRegistry(Database database, PolicyCache policyCache, Clock clock) {
    this.database = database;
    this.policyCache = policyCache;
    this.clock = clock;
  }

  public String issue(UUID userId, UUID deviceId) {
    String token = newToken();
    Instant now = clock.now();
    database.update(
        """
        INSERT INTO iam.sessions
            (session_token, user_id, device_id, issued_at, last_seen_at,
             absolute_expiry, idle_expiry)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        token,
        userId,
        deviceId,
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now.plus(ABSOLUTE_LIFETIME)),
        java.sql.Timestamp.from(now.plus(IDLE_LIFETIME)));
    return token;
  }

  /** Resolves a token, sliding the idle expiry. Returns empty for expired or unknown tokens. */
  public Optional<SessionView> resolve(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    return database.<Optional<SessionView>>asModule(
        ModuleRole.IAM,
        null,
        () -> {
          Map<String, Object> row =
              database.queryOne(
                  """
                  SELECT s.session_token, s.user_id, s.device_id, u.display_name, u.is_active
                  FROM iam.sessions s
                  JOIN iam.users u ON u.user_id = s.user_id
                  WHERE s.session_token = ?
                    AND s.absolute_expiry > now()
                    AND s.idle_expiry > now()
                  """,
                  token);
          if (row == null || !Boolean.TRUE.equals(row.get("is_active"))) {
            return Optional.<SessionView>empty();
          }
          UUID userId = (UUID) row.get("user_id");
          database.update(
              "UPDATE iam.sessions SET last_seen_at = now(), idle_expiry = now() + CAST(? AS interval)"
                  + " WHERE session_token = ?",
              IDLE_LIFETIME.toMinutes() + " minutes",
              token);
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

  public void revoke(String token) {
    database.asModule(
        ModuleRole.IAM, null, () -> database.update("DELETE FROM iam.sessions WHERE session_token = ?", token));
  }

  /**
   * Every session for one account, used when it is disabled or its access
   * changes. Runs in the caller's transaction so revocation commits with the
   * change that caused it rather than a moment later.
   */
  public int revokeAllFor(UUID userId) {
    policyCache.invalidate(userId);
    return database.update("DELETE FROM iam.sessions WHERE user_id = ?", userId);
  }

  public Actor actorOf(SessionView session) {
    return new Actor(session.userId(), session.deviceId());
  }

  private String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
