package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.shared.error.DomainException;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Slows down credential stuffing.
 *
 * <p>State lives in PostgreSQL rather than in memory, because an in-memory
 * counter means a second replica is a bypass: an attacker simply spreads
 * attempts across instances and the lockout never triggers.
 *
 * <p>Attempts are recorded whether they succeed or fail, so the same table also
 * answers "when did this account last sign in" without a second mechanism.
 */
@Component
public class LoginThrottle {
  private final Database database;
  private final int maxFailures;
  private final Duration window;

  public LoginThrottle(Database database, AppProperties properties) {
    this.database = database;
    this.maxFailures = properties.loginThrottle().maxFailures();
    this.window = properties.loginThrottle().window();
  }

  /** Throws when this identity has failed too often recently. */
  public void assertNotLockedOut(String email) {
    Map<String, Object> row =
        database.queryOne(
            """
            SELECT count(*) AS failures
            FROM iam.login_attempts
            WHERE email = ?
              AND succeeded = false
              AND attempted_at > now() - CAST(? AS interval)
            """,
            email,
            window.toSeconds() + " seconds");
    long failures = row == null ? 0 : ((Number) row.get("failures")).longValue();
    if (failures >= maxFailures) {
      throw DomainException.rateLimited(
          "Too many failed sign-in attempts. Try again in " + window.toMinutes() + " minutes.",
          window.toSeconds());
    }
  }

  public void record(String email, boolean succeeded, String sourceIp) {
    database.update(
        "INSERT INTO iam.login_attempts (email, succeeded, source_ip) VALUES (?, ?, ?::inet)",
        email,
        succeeded,
        sourceIp);
  }

  /** A successful sign-in clears the failure history, so a typo does not linger. */
  public void clearFailures(String email) {
    database.update(
        "DELETE FROM iam.login_attempts WHERE email = ? AND succeeded = false", email);
  }
}
