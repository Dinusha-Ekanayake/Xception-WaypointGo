package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.domain.auth.LockoutPolicy;
import com.waypoint.dispatch.platform.config.AppProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.error.DomainException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Slows down credential stuffing.
 *
 * <p>State lives in PostgreSQL rather than in memory, because an in-memory
 * counter means a second replica is a bypass: an attacker simply spreads
 * attempts across instances and the lockout never triggers.
 *
 * <p>Three counters, because each one alone has a hole. One identity from one
 * address is the usual lockout, and keying it on the pair is what stops a
 * stranger locking someone else's account from elsewhere. One address across
 * identities stops a machine walking a list of accounts. One identity across
 * addresses bounds a distributed guess, at a higher threshold.
 *
 * <p>Every method runs inside the sign-in's transaction. That transaction is
 * serializable, so two attempts that both count and then both insert cannot both
 * commit: one is retried and counts again, which is what closes the gap between
 * the check and the write.
 */
@Component
public class LoginThrottle {
  private final Database database;
  private final Metrics metrics;
  private final int pairMax;
  private final int addressMax;
  private final int identityMax;
  private final Duration window;

  public LoginThrottle(Database database, AppProperties properties, Metrics metrics) {
    this.database = database;
    this.metrics = metrics;
    this.pairMax = properties.loginThrottle().maxFailures();
    this.addressMax = properties.loginThrottle().addressMaxFailures();
    this.identityMax = properties.loginThrottle().identityMaxFailures();
    this.window = properties.loginThrottle().window();
  }

  /** Throws 429 with the wait when any counter has tripped. */
  public void assertNotLockedOut(String email, String sourceIp, Instant now) {
    check(
        "pair",
        pairMax,
        now,
        "email = ? AND source_ip IS NOT DISTINCT FROM ?::inet",
        email,
        sourceIp);
    check("identity", identityMax, now, "email = ?", email);
    if (sourceIp != null) {
      check("address", addressMax, now, "source_ip = ?::inet", sourceIp);
    }
  }

  public void record(String email, boolean succeeded, String sourceIp, Instant now) {
    database.update(
        "INSERT INTO iam.login_attempts (email, attempted_at, succeeded, source_ip)"
            + " VALUES (?, ?, ?, ?::inet)",
        email,
        Timestamp.from(now),
        succeeded,
        sourceIp);
  }

  /**
   * A successful sign-in stops this identity's earlier failures from this address
   * counting, so a typo does not linger. They are marked, not deleted: how often an
   * account was tried is worth knowing after the fact.
   */
  public void clearFailures(String email, String sourceIp, Instant now) {
    database.update(
        "UPDATE iam.login_attempts SET cleared_at = ?"
            + " WHERE email = ? AND source_ip IS NOT DISTINCT FROM ?::inet"
            + " AND NOT succeeded AND cleared_at IS NULL",
        Timestamp.from(now),
        email,
        sourceIp);
  }

  private void check(String counter, int threshold, Instant now, String where, Object... keys) {
    Object[] params = new Object[keys.length + 2];
    System.arraycopy(keys, 0, params, 0, keys.length);
    params[keys.length] = Timestamp.from(now.minus(window));
    params[keys.length + 1] = threshold;
    List<Instant> failures =
        database
            .query(
                "SELECT attempted_at FROM iam.login_attempts WHERE "
                    + where
                    + " AND NOT succeeded AND cleared_at IS NULL AND attempted_at > ?"
                    + " ORDER BY attempted_at DESC LIMIT ?",
                params)
            .stream()
            .map(row -> ((Timestamp) row.get("attempted_at")).toInstant())
            .toList();
    Optional<Duration> wait = LockoutPolicy.retryAfter(failures, threshold, window, now);
    if (wait.isPresent()) {
      metrics.increment("waypoint.login.locked_out", "counter", counter);
      long seconds = Math.max(1, wait.get().toSeconds());
      // No count and no counter name in the message: both are hints.
      throw DomainException.rateLimited(
          "Too many failed sign-in attempts. Try again later.", seconds);
    }
  }
}
