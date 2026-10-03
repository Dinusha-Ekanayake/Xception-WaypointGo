package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.infrastructure.Argon2PasswordHasher;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Everything that changes an account, a scope or a driver assignment.
 *
 * <p>Two ways in, one body, on purpose. The {@code apply*} methods assume a
 * transaction is already open and are what the command handlers in this package
 * call, so an administrator's change gets a receipt and an audit row that commit
 * with it. The public methods open their own transaction and exist for the host
 * commands: the first administrator cannot come from an endpoint that requires
 * an administrator.
 *
 * <p>Every method records who acted, and from which device. An audit row that
 * names the account that was changed but not the person who changed it answers
 * the wrong question. A host command has no actor, and passes null.
 *
 * <p>An account's role, scopes and vehicle assignments are part of the account,
 * so each change to them is guarded by the account's version and moves it. Each
 * one that can alter a decision also moves the policy generation, and each one
 * that takes something away revokes the account's sessions in the same
 * transaction (SEC-02).
 */
@Component
public class AccountAdminUseCase {
  private static final int MINIMUM_PASSWORD_LENGTH = 12;

  private final Database database;
  private final Argon2PasswordHasher hasher;
  private final SessionRegistry sessions;
  private final PolicyGeneration generation;
  private final AuditLog audit;
  private final Metrics metrics;

  public AccountAdminUseCase(
      Database database,
      Argon2PasswordHasher hasher,
      SessionRegistry sessions,
      PolicyGeneration generation,
      AuditLog audit,
      Metrics metrics) {
    this.database = database;
    this.hasher = hasher;
    this.sessions = sessions;
    this.generation = generation;
    this.audit = audit;
    this.metrics = metrics;
  }

  // ---- host commands: they open the transaction themselves ----

  public UUID createAccount(String email, String displayName, String password, String roleCode) {
    return database.asModule(
        ModuleRole.IAM, null, () -> applyCreate(null, email, displayName, password, roleCode));
  }

  public int disableAccount(String email) {
    return database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          UUID userId = userIdOf(email.trim().toLowerCase(Locale.ROOT));
          // No version to quote from a terminal. Safe here and only here: the read
          // and the write are one serializable transaction with one operator, so
          // there is no other writer to lose an update to.
          return applyDisable(null, userId, versionOf(userId));
        });
  }

  public void grantDepot(String email, String depotCode) {
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          UUID userId = userIdOf(email.trim().toLowerCase(Locale.ROOT));
          applyGrantDepot(null, userId, depotCode, versionOf(userId));
        });
  }

  public void grantOutlet(String email, String outletId) {
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          UUID userId = userIdOf(email.trim().toLowerCase(Locale.ROOT));
          applyGrantOutlet(null, userId, outletId, versionOf(userId));
        });
  }

  /**
   * Gives a driver one day on each vehicle that has no driver that day. For the
   * delivery-day seed: the plan decides later which vehicle carries what.
   *
   * @return the vehicles assigned
   */
  public int assignDriverForDay(String email, List<String> vehicleIds, LocalDate day) {
    int assigned = 0;
    for (String vehicleId : vehicleIds) {
      try {
        database.asModule(
            ModuleRole.IAM,
            null,
            () -> {
              UUID userId = userIdOf(email.trim().toLowerCase(Locale.ROOT));
              applyAssignDriver(null, vehicleId, userId, day, day.plusDays(1), versionOf(userId));
            });
        assigned++;
      } catch (DomainException e) {
        if (e.code() != ErrorCode.CONFLICT) {
          throw e;
        }
        // The vehicle already has a driver that day; leave it.
      }
    }
    return assigned;
  }

  // ---- command bodies: a transaction is already open ----

  UUID applyCreate(
      Actor actor, String email, String displayName, String password, String roleCode) {
    String normalised = normaliseEmail(email);
    requirePassword(password);
    if (displayName == null || displayName.isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "displayName is required");
    }
    if (database.queryOne("SELECT role_code FROM iam.roles WHERE role_code = ?", roleCode) == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown role " + roleCode);
    }
    if (database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", normalised) != null) {
      throw new DomainException(ErrorCode.CONFLICT, "An account with that email already exists");
    }

    UUID userId = UUID.randomUUID();
    database.update(
        "INSERT INTO iam.users (user_id, email, display_name, password_hash) VALUES (?, ?, ?, ?)",
        userId,
        normalised,
        displayName.trim(),
        hasher.hash(password));
    database.update(
        "INSERT INTO iam.user_roles (user_id, role_code) VALUES (?, ?)", userId, roleCode);
    record(actor, userId, "iam:CreateUser", "role " + roleCode);
    return userId;
  }

  /**
   * Email and display name only. A role change is a scope change and goes through
   * its own command, so "I edited the name" can never quietly grant something.
   */
  void applyUpdate(
      Actor actor, UUID userId, String displayName, String email, Long expectedVersion) {
    if (displayName == null && email == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Nothing to change: give displayName, email, or both");
    }
    Map<String, Object> current = requireUser(userId);
    String newEmail = email == null ? (String) current.get("email") : normaliseEmail(email);
    String newName =
        displayName == null ? (String) current.get("display_name") : displayName.trim();

    if (!newEmail.equals(current.get("email"))
        && database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", newEmail) != null) {
      throw new DomainException(
          ErrorCode.CONFLICT, "That email already belongs to another account");
    }

    guardVersion(userId, expectedVersion, current);
    database.updateExpectingOneRow(
        "UPDATE iam.users SET email = ?, display_name = ?, row_version = row_version + 1,"
            + " updated_at = now() WHERE user_id = ? AND row_version = ?",
        newEmail,
        newName,
        userId,
        current.get("row_version"));
    record(actor, userId, "iam:UpdateUser", "email and display name reviewed");
  }

  /**
   * Disabling revokes every session in the same transaction. A disabled account
   * that keeps a live session until it expires is the failure that made sessions
   * server side in the first place.
   */
  int applyDisable(Actor actor, UUID userId, Long expectedVersion) {
    Map<String, Object> current = requireUser(userId);
    guardVersion(userId, expectedVersion, current);
    refuseIfLastDispatcher(userId, null);
    database.updateExpectingOneRow(
        "UPDATE iam.users SET is_active = false, row_version = row_version + 1,"
            + " updated_at = now() WHERE user_id = ? AND row_version = ?",
        userId,
        current.get("row_version"));
    int revoked = sessions.revokeAllFor(userId, "disabled");
    generation.advance();
    record(
        actor,
        userId,
        "iam:DisableUser",
        "disabled, " + revoked + " session(s) revoked");
    return revoked;
  }

  /**
   * Every session goes with the password. A reset that leaves the old sessions
   * alive protects nobody, because the reason to reset is usually that someone
   * else has the account.
   */
  void applyResetPassword(Actor actor, UUID userId, String password, Long expectedVersion) {
    requirePassword(password);
    Map<String, Object> current = requireUser(userId);
    guardVersion(userId, expectedVersion, current);
    database.updateExpectingOneRow(
        "UPDATE iam.users SET password_hash = ?, row_version = row_version + 1,"
            + " updated_at = now() WHERE user_id = ? AND row_version = ?",
        hasher.hash(password),
        userId,
        current.get("row_version"));
    int revoked = sessions.revokeAllFor(userId, "password");
    record(actor, userId, "iam:ResetPassword", revoked + " session(s) revoked with it");
  }

  /**
   * One role replaces whatever the account held. The sessions go with it in the
   * same transaction: a session opened as a dispatcher must not carry on as
   * whatever the account is now, and the person signs in again as that.
   */
  int applyChangeRole(Actor actor, UUID userId, String roleCode, Long expectedVersion) {
    Map<String, Object> current = requireUser(userId);
    guardVersion(userId, expectedVersion, current);
    if (database.queryOne("SELECT role_code FROM iam.roles WHERE role_code = ?", roleCode) == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown role " + roleCode);
    }
    if (!"dispatcher".equals(roleCode)) {
      refuseIfLastDispatcher(userId, null);
    }
    database.updateExpectingOneRow(
        "UPDATE iam.users SET row_version = row_version + 1, updated_at = now()"
            + " WHERE user_id = ? AND row_version = ?",
        userId,
        current.get("row_version"));
    database.update("DELETE FROM iam.user_roles WHERE user_id = ?", userId);
    database.update(
        "INSERT INTO iam.user_roles (user_id, role_code) VALUES (?, ?)", userId, roleCode);
    int revoked = sessions.revokeAllFor(userId, "role");
    generation.advance();
    record(
        actor,
        userId,
        "iam:ChangeRole",
        "role is now " + roleCode + ", " + revoked + " session(s) revoked");
    return revoked;
  }

  void applyGrantDepot(Actor actor, UUID userId, String depotCode, Long expectedVersion) {
    touchAccount(userId, expectedVersion);
    requireDepot(depotCode);
    database.update(
        "INSERT INTO iam.user_depot_access (user_id, depot_code) VALUES (?, ?)"
            + " ON CONFLICT DO NOTHING",
        userId,
        depotCode);
    generation.advance();
    record(actor, userId, "iam:GrantScope", "depot " + depotCode + " granted");
  }

  /**
   * Taking a scope away revokes the account's sessions. A session resolved a
   * moment ago carries the old scope in what the client was told, and the person
   * should see the smaller world on their next screen, not their next sign-in.
   */
  void applyRevokeDepot(Actor actor, UUID userId, String depotCode, Long expectedVersion) {
    touchAccount(userId, expectedVersion);
    refuseIfLastDispatcher(userId, depotCode);
    int removed =
        database.update(
            "DELETE FROM iam.user_depot_access WHERE user_id = ? AND depot_code = ?",
            userId,
            depotCode);
    // Not an error: revoking what was never granted leaves the intended state.
    record(
        actor,
        userId,
        "iam:RevokeScope",
        "depot " + depotCode + (removed == 0 ? " was not granted" : " revoked"));
    revokeForScope(userId, removed);
  }

  void applyGrantOutlet(Actor actor, UUID userId, String outletId, Long expectedVersion) {
    touchAccount(userId, expectedVersion);
    requireOutlet(outletId);
    database.update(
        "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)"
            + " ON CONFLICT DO NOTHING",
        userId,
        outletId);
    generation.advance();
    record(actor, userId, "iam:GrantScope", "outlet " + outletId + " granted");
  }

  void applyRevokeOutlet(Actor actor, UUID userId, String outletId, Long expectedVersion) {
    touchAccount(userId, expectedVersion);
    int removed =
        database.update(
            "DELETE FROM iam.user_outlet_access WHERE user_id = ? AND outlet_id = ?",
            userId,
            outletId);
    record(
        actor,
        userId,
        "iam:RevokeScope",
        "outlet " + outletId + (removed == 0 ? " was not granted" : " revoked"));
    revokeForScope(userId, removed);
  }

  private void revokeForScope(UUID userId, int removed) {
    if (removed > 0) {
      sessions.revokeAllFor(userId, "scope");
    }
    generation.advance();
  }

  /**
   * A driver holds a vehicle for a period, not forever, so yesterday's driver
   * cannot post today's delivery. Overlap is refused by the exclusion constraint
   * in {@code iam.vehicle_driver_assignments}, which is why this does not check
   * for it first: a check plus an insert is a race, a constraint is not.
   *
   * <p>The version is the driver's account. An assignment is a new row, so what
   * can go stale under the caller is the account it is given to: disabled, or no
   * longer a driver.
   *
   * @param until null for open ended
   */
  UUID applyAssignDriver(
      Actor actor,
      String vehicleId,
      UUID driverUserId,
      LocalDate from,
      LocalDate until,
      Long expectedVersion) {
    touchAccount(driverUserId, expectedVersion);
    requireVehicle(vehicleId);
    if (until != null && !until.isAfter(from)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "until must be after from; the range is half open");
    }
    if (!isDriver(driverUserId)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Only an account with the driver role can hold a vehicle");
    }

    UUID assignmentId = UUID.randomUUID();
    try {
      database.update(
          "INSERT INTO iam.vehicle_driver_assignments"
              + " (assignment_id, vehicle_id, driver_user_id, validity, assigned_by)"
              + " VALUES (?, ?, ?, daterange(?, ?, '[)'), ?)",
          assignmentId,
          vehicleId,
          driverUserId,
          java.sql.Date.valueOf(from),
          until == null ? null : java.sql.Date.valueOf(until),
          actor == null ? null : actor.userId());
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      // FLT-03, SEC-03: the exclusion constraint caught an overlap, possibly one a
      // concurrent assignment created after this one's checks passed.
      metrics.increment("waypoint.race.lost", "path", "iam:AssignDriver");
      throw new DomainException(
          ErrorCode.CONFLICT,
          vehicleId + " already has a driver for part of that period",
          List.of("R-IAM-13"));
    }
    record(
        actor,
        driverUserId,
        "iam:AssignDriver",
        vehicleId + " from " + from + (until == null ? " open ended" : " until " + until));
    return assignmentId;
  }

  /** Shortens an assignment rather than deleting it: who held the vehicle stays answerable. */
  void applyEndDriverAssignment(
      Actor actor, UUID assignmentId, LocalDate on, Long expectedVersion) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT vehicle_id, driver_user_id, lower(validity) AS starts_on, row_version"
                + " FROM iam.vehicle_driver_assignments WHERE assignment_id = ?",
            assignmentId);
    if (row == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No assignment " + assignmentId);
    }
    guardVersion("Assignment " + assignmentId, expectedVersion, row);
    LocalDate startsOn = ((java.sql.Date) row.get("starts_on")).toLocalDate();
    if (!on.isAfter(startsOn)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "An assignment that started on " + startsOn + " cannot end on " + on);
    }
    database.updateExpectingOneRow(
        "UPDATE iam.vehicle_driver_assignments SET validity = daterange(lower(validity), ?, '[)'),"
            + " row_version = row_version + 1 WHERE assignment_id = ? AND row_version = ?",
        java.sql.Date.valueOf(on),
        assignmentId,
        row.get("row_version"));
    record(
        actor,
        (UUID) row.get("driver_user_id"),
        "iam:AssignDriver",
        row.get("vehicle_id") + " released on " + on);
  }

  // ---- shared checks ----

  UUID userIdOf(String email) {
    Map<String, Object> row =
        database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", email);
    if (row == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No account with that email");
    }
    return (UUID) row.get("user_id");
  }

  private long versionOf(UUID userId) {
    return ((Number) requireUser(userId).get("row_version")).longValue();
  }

  private Map<String, Object> requireUser(UUID userId) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT email, display_name, row_version FROM iam.users WHERE user_id = ?", userId);
    if (row == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No account " + userId);
    }
    return row;
  }

  private boolean isDriver(UUID userId) {
    return database.queryOne(
            "SELECT role_code FROM iam.user_roles WHERE user_id = ? AND role_code = 'driver'",
            userId)
        != null;
  }

  /**
   * A scope naming something that does not exist is a permanent silent denial,
   * which is the same class of mistake as an action missing from the catalogue.
   */
  private void requireDepot(String depotCode) {
    boolean known =
        database.queryOne(
                "SELECT d.depot_code FROM ref.depots d"
                    + " JOIN ref.reference_versions v"
                    + " ON v.reference_version_id = d.reference_version_id AND v.is_current"
                    + " WHERE d.depot_code = ?",
                depotCode)
            != null;
    if (!known) {
      throw new DomainException(
          ErrorCode.NOT_FOUND, "No depot " + depotCode + " in the current reference version");
    }
  }

  private void requireOutlet(String outletId) {
    if (database.queryOne("SELECT outlet_id FROM ref.outlet_registry WHERE outlet_id = ?", outletId)
        == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No outlet " + outletId);
    }
  }

  private void requireVehicle(String vehicleId) {
    if (database.queryOne(
            "SELECT vehicle_id FROM ref.vehicle_registry WHERE vehicle_id = ?", vehicleId)
        == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No vehicle " + vehicleId);
    }
  }

  private void guardVersion(UUID userId, Long expectedVersion, Map<String, Object> current) {
    guardVersion("Account " + userId, expectedVersion, current);
  }

  private static void guardVersion(String what, Long expectedVersion, Map<String, Object> current) {
    if (expectedVersion == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "expectedVersion is required to change " + what);
    }
    long actual = ((Number) current.get("row_version")).longValue();
    if (actual != expectedVersion) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT, what + " is at version " + actual + ", not " + expectedVersion);
    }
  }

  /**
   * Guards a change to what an account holds (a scope, a vehicle) with the
   * account's version, and moves it. The update is the guard: zero rows means
   * someone else changed the account first.
   */
  private void touchAccount(UUID userId, Long expectedVersion) {
    Map<String, Object> current = requireUser(userId);
    guardVersion(userId, expectedVersion, current);
    database.updateExpectingOneRow(
        "UPDATE iam.users SET row_version = row_version + 1, updated_at = now()"
            + " WHERE user_id = ? AND row_version = ?",
        userId,
        current.get("row_version"));
  }

  /**
   * R-IAM-18. A depot whose only active dispatcher is disabled, moved to another
   * role or taken off the depot has nobody who can publish its plan, and the
   * first anyone hears of it is the morning nothing is dispatched.
   *
   * @param depotCode the one depot being taken away, or null for all the account holds
   */
  private void refuseIfLastDispatcher(UUID userId, String depotCode) {
    List<String> orphaned =
        database
            .query(
                """
                SELECT a.depot_code
                FROM iam.user_depot_access a
                JOIN iam.users self ON self.user_id = a.user_id AND self.is_active
                JOIN iam.user_roles held
                  ON held.user_id = a.user_id AND held.role_code = 'dispatcher'
                WHERE a.user_id = ?
                  AND (?::text IS NULL OR a.depot_code = ?::text)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM iam.user_depot_access o
                      JOIN iam.users u ON u.user_id = o.user_id AND u.is_active
                      JOIN iam.user_roles r
                        ON r.user_id = o.user_id AND r.role_code = 'dispatcher'
                      WHERE o.depot_code = a.depot_code AND o.user_id <> a.user_id)
                ORDER BY a.depot_code
                """,
                userId,
                depotCode,
                depotCode)
            .stream()
            .map(r -> (String) r.get("depot_code"))
            .toList();
    if (!orphaned.isEmpty()) {
      throw new DomainException(
          ErrorCode.CONFLICT,
          "This is the last active dispatcher for "
              + String.join(", ", orphaned)
              + ". Give the depot another dispatcher first.",
          List.of("R-IAM-18"));
    }
  }

  private static String normaliseEmail(String email) {
    String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (!normalised.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Not a valid email address");
    }
    return normalised;
  }

  private static void requirePassword(String password) {
    if (password == null || password.length() < MINIMUM_PASSWORD_LENGTH) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "A password must be at least " + MINIMUM_PASSWORD_LENGTH + " characters");
    }
  }

  /** The actor is the administrator; the subject is the account that changed. */
  private void record(Actor actor, UUID subjectId, String action, String reason) {
    audit.record(
        AuditEntry.allowed(
            actor == null ? subjectId : actor.userId(),
            actor == null ? null : actor.deviceId(),
            action,
            "wpt:iam:user:" + subjectId,
            reason));
  }
}
