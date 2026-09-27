package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.infrastructure.Argon2PasswordHasher;
import com.waypoint.dispatch.identity.infrastructure.PolicyCache;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Account lifecycle, for a trusted operator rather than the public API.
 *
 * <p>The first administrator has to come from somewhere, and it cannot come from
 * an endpoint that requires an administrator. This runs from the host as a
 * command, the same way migrations do.
 */
@Component
public class AccountAdminUseCase {
  private final Database database;
  private final Argon2PasswordHasher hasher;
  private final SessionRegistry sessions;
  private final PolicyCache policyCache;
  private final AuditLog audit;

  public AccountAdminUseCase(
      Database database,
      Argon2PasswordHasher hasher,
      SessionRegistry sessions,
      PolicyCache policyCache,
      AuditLog audit) {
    this.database = database;
    this.hasher = hasher;
    this.sessions = sessions;
    this.policyCache = policyCache;
    this.audit = audit;
  }

  public UUID createAccount(String email, String displayName, String password, String roleCode) {
    String normalised = email.trim().toLowerCase(Locale.ROOT);
    if (!normalised.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Not an email address: " + email);
    }
    if (password == null || password.length() < 12) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "A password must be at least 12 characters");
    }

    return database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          if (database.queryOne("SELECT role_code FROM iam.roles WHERE role_code = ?", roleCode)
              == null) {
            throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown role " + roleCode);
          }
          if (database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", normalised)
              != null) {
            throw new DomainException(ErrorCode.CONFLICT, normalised + " already exists");
          }

          UUID userId = UUID.randomUUID();
          database.update(
              "INSERT INTO iam.users (user_id, email, display_name, password_hash)"
                  + " VALUES (?, ?, ?, ?)",
              userId,
              normalised,
              displayName,
              hasher.hash(password));
          database.update(
              "INSERT INTO iam.user_roles (user_id, role_code) VALUES (?, ?)", userId, roleCode);
          audit.record(
              AuditEntry.allowed(
                  userId, null, "iam:CreateUser", "wpt:iam:user:" + userId, "role " + roleCode));
          return userId;
        });
  }

  /**
   * Disabling revokes every session in the same transaction. A disabled account
   * that keeps a live session until it expires is the failure that made sessions
   * server side in the first place.
   */
  public int disableAccount(String email) {
    String normalised = email.trim().toLowerCase(Locale.ROOT);
    return database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          Map<String, Object> user =
              database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", normalised);
          if (user == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "No account for " + normalised);
          }
          UUID userId = (UUID) user.get("user_id");
          database.updateExpectingOneRow(
              "UPDATE iam.users SET is_active = false, row_version = row_version + 1"
                  + " WHERE user_id = ?",
              userId);
          int revoked = sessions.revokeAllFor(userId);
          policyCache.invalidate(userId);
          audit.record(
              AuditEntry.allowed(
                  userId,
                  null,
                  "iam:DisableUser",
                  "wpt:iam:user:" + userId,
                  "disabled, " + revoked + " session(s) revoked"));
          return revoked;
        });
  }

  /** Grants a depot to an account. The coarse boundary that row-level security filters on. */
  public void grantDepot(String email, String depotCode) {
    String normalised = email.trim().toLowerCase(Locale.ROOT);
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          Map<String, Object> user =
              database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", normalised);
          if (user == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "No account for " + normalised);
          }
          UUID userId = (UUID) user.get("user_id");
          database.update(
              "INSERT INTO iam.user_depot_access (user_id, depot_code) VALUES (?, ?)"
                  + " ON CONFLICT DO NOTHING",
              userId,
              depotCode);
          audit.record(
              AuditEntry.allowed(
                  userId,
                  null,
                  "iam:GrantScope",
                  "wpt:ref:depot:" + depotCode,
                  "depot access granted"));
        });
  }
}
