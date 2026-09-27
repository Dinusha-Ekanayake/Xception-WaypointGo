package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
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
  private static final int MAX_FAILURES = 8;
  private static final Duration WINDOW = Duration.ofMinutes(15);

  private final Database database;

  public LoginThrottle(Database database) {
    this.database = database;
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
            WINDOW.toMinutes() + " minutes");
    long failures = row == null ? 0 : ((Number) row.get("failures")).longValue();
    if (failures >= MAX_FAILURES) {
      throw new DomainException(
          ErrorCode.FORBIDDEN,
          "Too many failed sign-in attempts. Try again in " + WINDOW.toMinutes() + " minutes.");
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
