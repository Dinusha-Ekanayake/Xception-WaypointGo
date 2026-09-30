package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.infrastructure.Argon2PasswordHasher;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
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
 */
@Component
public class LoginHandler {
  private final Database database;
  private final Argon2PasswordHasher hasher;
  private final LoginThrottle throttle;
  private final SessionRegistry sessions;
  private final AuditLog audit;
  private final Metrics metrics;

  public LoginHandler(
      Database database,
      Argon2PasswordHasher hasher,
      LoginThrottle throttle,
      SessionRegistry sessions,
      AuditLog audit,
      Metrics metrics) {
    this.database = database;
    this.hasher = hasher;
    this.throttle = throttle;
    this.sessions = sessions;
    this.audit = audit;
    this.metrics = metrics;
  }

  /** @return an opaque session token */
  public String login(String email, String password, UUID deviceId, String sourceIp) {
    String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

    return database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          throttle.assertNotLockedOut(normalised);

          Map<String, Object> user =
              database.queryOne(
                  "SELECT user_id, password_hash, is_active FROM iam.users WHERE email = ?",
                  normalised);

          if (user == null) {
            // Spend the same work as a real verification, so a missing account
            // is not measurably faster than a wrong password.
            hasher.verifyDummy(password);
            return reject(normalised, sourceIp, null);
          }
          if (!hasher.matches(password, String.valueOf(user.get("password_hash")))) {
            return reject(normalised, sourceIp, (UUID) user.get("user_id"));
          }
          if (!Boolean.TRUE.equals(user.get("is_active"))) {
            return reject(normalised, sourceIp, (UUID) user.get("user_id"));
          }

          UUID userId = (UUID) user.get("user_id");
          throttle.record(normalised, true, sourceIp);
          throttle.clearFailures(normalised);
          String token = sessions.issue(userId, deviceId);
          audit.record(
              AuditEntry.allowed(userId, deviceId, "iam:Login", "wpt:iam:user:" + userId, "signed in"));
          metrics.increment("waypoint.login.succeeded");
          return token;
        });
  }

  private String reject(String email, String sourceIp, UUID userId) {
    throttle.record(email, false, sourceIp);
    audit.record(
        AuditEntry.denied(
            userId, null, "iam:Login", "wpt:iam:user:" + email, "invalid credentials"));
    metrics.increment("waypoint.login.failed");
    // One message for every failure mode. Anything more specific is a hint.
    throw new DomainException(ErrorCode.UNAUTHENTICATED, "Email or password is incorrect");
  }
}
