package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.infrastructure.Argon2PasswordHasher;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Signs a person in.
 *
 * <p>Three things this deliberately does not do:
 *
 * <ul>
 *   <li>Distinguish an unknown account from a wrong password, in the message or
 *       in the time taken. Either would let someone enumerate valid accounts.
 *   <li>Trust a disabled account. The check is here, not only at session
 *       resolution, so a disabled user cannot obtain a token at all.
 *   <li>Report why a lockout happened beyond "too many attempts", since the
 *       count itself is information.
 * </ul>
 *
 * <p>A wrong password is an outcome, not an exception, until the transaction has
 * committed. Throwing inside it rolled back the failed attempt and its audit row
 * along with everything else, so the lockout had nothing to count and a denied
 * sign-in left no trace.
 */
@Component
public class LoginHandler {
  private final Database database;
  private final Argon2PasswordHasher hasher;
  private final LoginThrottle throttle;
  private final SessionRegistry sessions;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;

  public LoginHandler(
      Database database,
      Argon2PasswordHasher hasher,
      LoginThrottle throttle,
      SessionRegistry sessions,
      AuditLog audit,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.hasher = hasher;
    this.throttle = throttle;
    this.sessions = sessions;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock.realTime();
  }

  /** @return an opaque session token */
  public String login(String email, String password, UUID deviceId, String sourceIp) {
    return login(email, password, deviceId, sourceIp, null);
  }

  /**
   * @param previousToken the session cookie the caller already held, if any. It is revoked with
   *     the new sign-in, so signing in again on a shared tablet does not leave the last person's
   *     session alive behind the new one
   * @return an opaque session token
   */
  public String login(
      String email, String password, UUID deviceId, String sourceIp, String previousToken) {
    return login(email, password, deviceId, sourceIp, previousToken, false);
  }

  /** Personal credentials only: no device or PIN-switched supervisor identity. */
  public String loginMcp(String email, String password, String sourceIp) {
    return login(email, password, null, sourceIp, null, true);
  }

  /**
   * Proves who someone is for a remote MCP authorization, with the same throttle,
   * timing and audit as a sign-in, and issues nothing. The session is created
   * later, when the client exchanges its one-time code (R-IAM-31).
   */
  public UUID verifyMcp(String email, String password, String sourceIp) {
    return run(email, password, null, sourceIp, null, Issue.NOTHING).userId();
  }

  private String login(
      String email, String password, UUID deviceId, String sourceIp, String previousToken,
      boolean mcpReadOnly) {
    Issue issue = mcpReadOnly ? Issue.MCP_SESSION : Issue.BROWSER_SESSION;
    return run(email, password, deviceId, sourceIp, previousToken, issue).token();
  }

  /** What a successful sign-in hands back. */
  private enum Issue {
    BROWSER_SESSION,
    MCP_SESSION,
    NOTHING
  }

  private record Proven(UUID userId, String token) {}

  private Proven run(
      String email, String password, UUID deviceId, String sourceIp, String previousToken,
      Issue issue) {
    String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

    Proven proven =
        database.asModule(
            ModuleRole.IAM, null, () -> attempt(normalised, password, deviceId, sourceIp, previousToken, issue));
    if (proven == null) {
      metrics.increment("waypoint.login.failed");
      // One message for every failure mode. Anything more specific is a hint.
      throw new DomainException(ErrorCode.UNAUTHENTICATED, "Email or password is incorrect");
    }
    metrics.increment("waypoint.login.succeeded");
    return proven;
  }

  /** Who signed in, or null for a refused sign-in whose record must still commit. */
  private Proven attempt(
      String email, String password, UUID deviceId, String sourceIp, String previousToken,
      Issue issue) {
    Instant now = clock.now();
    throttle.assertNotLockedOut(email, sourceIp, now);

    Map<String, Object> user =
        database.queryOne(
            "SELECT user_id, password_hash, is_active FROM iam.users WHERE email = ?", email);

    if (user == null) {
      // Spend the same work as a real verification, so a missing account
      // is not measurably faster than a wrong password.
      hasher.verifyDummy(password);
      return reject(email, sourceIp, null, now);
    }
    UUID userId = (UUID) user.get("user_id");
    if (!hasher.matches(password, String.valueOf(user.get("password_hash")))) {
      return reject(email, sourceIp, userId, now);
    }
    if (!Boolean.TRUE.equals(user.get("is_active"))) {
      return reject(email, sourceIp, userId, now);
    }

    // Only someone who has just proved who they are learns whether a device exists.
    requireRegisteredDevice(deviceId, now);

    throttle.record(email, true, sourceIp, now);
    throttle.clearFailures(email, sourceIp, now);
    if (previousToken != null && !previousToken.isBlank()) {
      sessions.revokeInTransaction(previousToken);
    }
    String token =
        switch (issue) {
          case BROWSER_SESSION -> sessions.issue(userId, deviceId);
          case MCP_SESSION -> sessions.issue(userId, null, true);
          case NOTHING -> null;
        };
    audit.record(
        AuditEntry.allowed(
            userId,
            deviceId,
            "iam:Login",
            "wpt:iam:user:" + userId,
            issue == Issue.NOTHING ? "credentials verified for a remote MCP authorization" : "signed in"));
    return new Proven(userId, token);
  }

  private Proven reject(String email, String sourceIp, UUID userId, Instant now) {
    throttle.record(email, false, sourceIp, now);
    audit.record(
        AuditEntry.denied(
            userId,
            null,
            "iam:Login",
            // Never the email: an audit resource is personal data kept for years.
            "wpt:iam:user:" + (userId == null ? "unknown" : userId),
            "invalid credentials"));
    return null;
  }

  /**
   * A device identity is granted by an administrator, not claimed by a client. An
   * unknown or retired one is the caller's mistake (422), where it used to reach
   * the session insert and fail its foreign key as a 500.
   */
  private void requireRegisteredDevice(UUID deviceId, Instant now) {
    if (deviceId == null) {
      return;
    }
    int seen =
        database.update(
            "UPDATE iam.devices SET last_seen_at = ? WHERE device_id = ? AND is_active",
            java.sql.Timestamp.from(now),
            deviceId);
    if (seen == 0) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "deviceId is not a registered device. Sign in without it, or ask an administrator to"
              + " register this device.");
    }
  }
}
