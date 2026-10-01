package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.SessionView;
import com.waypoint.dispatch.identity.domain.OfflineSwitchPolicy;
import com.waypoint.dispatch.identity.domain.OfflineSwitchPolicy.Switch;
import com.waypoint.dispatch.identity.domain.PinPolicy;
import com.waypoint.dispatch.identity.domain.PinVerifier;
import com.waypoint.dispatch.identity.infrastructure.Argon2PasswordHasher;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who is working on a shared dock device right now (decision 2026-10-01).
 *
 * <p>A loader supervisor signs the device in; loaders switch to themselves with
 * a PIN, and the operator is the actor on every write. Lock and Switch user end
 * the operator; the device stays signed in until the supervisor signs it out.
 *
 * <p>An attempt is recorded and committed before the answer is returned, so a
 * wrong PIN counts toward the pause instead of rolling back with an exception.
 */
@Component
public class OperatorRegistry {
  public static final String LOADER_ROLE = "loader";
  private static final Duration FAILURE_WINDOW = Duration.ofMinutes(30);

  private final Database database;
  private final Argon2PasswordHasher hasher;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public OperatorRegistry(
      Database database, Argon2PasswordHasher hasher, AuditLog audit, Metrics metrics, Clock clock) {
    this.database = database;
    this.hasher = hasher;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** @param offlineVerifier the PIN check this device may run offline; empty until the PIN is used online once */
  public record CrewMember(UUID userId, String displayName, String employeeCode, Optional<String> offlineVerifier) {}

  public record Operator(UUID userId, String displayName, Optional<String> employeeCode, Instant since) {}

  /** What a PIN attempt came to. Never thrown, so the attempt commits. */
  public sealed interface SwitchResult permits Switched, WrongPin, Paused {}

  public record Switched(Operator operator) implements SwitchResult {}

  public record WrongPin(int triesLeft) implements SwitchResult {}

  public record Paused(Duration remaining) implements SwitchResult {}

  /** Loaders who share a depot with the device's account and have a PIN, by name (Figma 00). */
  public List<CrewMember> crew(SessionView device) {
    requireLoaderDevice(device);
    return database.asModule(
        ModuleRole.IAM,
        null,
        () ->
            database
                .query(
                    """
                    SELECT DISTINCT u.user_id, u.display_name, u.employee_code, u.pin_offline_verifier
                    FROM iam.users u
                    JOIN iam.user_roles r ON r.user_id = u.user_id AND r.role_code = ?
                    JOIN iam.user_depot_access d ON d.user_id = u.user_id
                    WHERE u.is_active AND u.pin_hash IS NOT NULL
                      AND d.depot_code IN (SELECT depot_code FROM iam.user_depot_access WHERE user_id = ?)
                    ORDER BY u.display_name
                    """,
                    LOADER_ROLE,
                    device.userId())
                .stream()
                .map(r -> new CrewMember(
                    (UUID) r.get("user_id"), (String) r.get("display_name"), (String) r.get("employee_code"),
                    Optional.ofNullable((String) r.get("pin_offline_verifier"))))
                .toList());
  }

  /** Switch the device to a crew member who proves it with their PIN. */
  public SwitchResult switchTo(String token, SessionView device, UUID userId, String pin) {
    requireLoaderDevice(device);
    PinPolicy.requireWellFormed(pin);
    CrewMember member =
        crew(device).stream()
            .filter(c -> c.userId().equals(userId))
            .findFirst()
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "Not on this device's crew list"));
    Instant now = clock.now();
    String key = key(token);

    SwitchResult result =
        database.asModule(
            ModuleRole.IAM,
            null,
            () -> {
              // Revalidate the candidate in the same transaction as the PIN
              // attempt. The crew list is a display query, not authorization.
              Map<String, Object> row =
                  database.queryOne(
                      """
                      SELECT u.pin_hash, u.display_name, u.employee_code
                      FROM iam.users u
                      JOIN iam.user_roles r ON r.user_id = u.user_id AND r.role_code = ?
                      JOIN iam.user_depot_access candidate ON candidate.user_id = u.user_id
                      JOIN iam.user_depot_access device ON device.depot_code = candidate.depot_code
                        AND device.user_id = ?
                      WHERE u.user_id = ? AND u.is_active
                      LIMIT 1
                      FOR UPDATE OF u
                      """,
                      LOADER_ROLE, device.userId(), userId);
              if (row == null) {
                throw new DomainException(ErrorCode.NOT_FOUND, "Not on this device's crew list");
              }
              List<Instant> failures = recentFailures(userId, now);
              Optional<Duration> paused = PinPolicy.pausedFor(failures, now);
              if (paused.isPresent()) {
                return new Paused(paused.get());
              }
              boolean ok = row != null && hasher.matches(pin, (String) row.get("pin_hash"));
              database.update(
                  "INSERT INTO iam.pin_attempts (user_id, attempted_at, succeeded, session_key) VALUES (?, ?, ?, ?)",
                  userId, Timestamp.from(now), ok, key);
              if (!ok) {
                audit.record(AuditEntry.denied(userId, device.deviceId(), "iam:SwitchOperator",
                    "wpt:iam:user:" + userId, "wrong PIN"));
                return new WrongPin(PinPolicy.triesLeft(failures.size() + 1));
              }
              closeOpen(key, now, "switch");
              database.update(
                  "INSERT INTO iam.session_operators (session_key, user_id, started_at) VALUES (?, ?, ?)",
                  key, userId, Timestamp.from(now));
              // The PIN was right, so this is the moment to write its offline check;
              // accounts provisioned before offline switching get one here.
              database.update("UPDATE iam.users SET pin_offline_verifier = ? WHERE user_id = ?",
                  verifier(pin), userId);
              database.update(
                  "UPDATE iam.sessions SET operator_user_id = ?, operator_since = ? WHERE session_token = ?",
                  userId, Timestamp.from(now), token);
              audit.record(AuditEntry.allowed(userId, device.deviceId(), "iam:SwitchOperator",
                  "wpt:iam:user:" + userId, "operator on a shared device"));
              return new Switched(new Operator(
                  userId, member.displayName(), Optional.ofNullable(member.employeeCode()), now));
            });
    metrics.increment("waypoint.operator.switch", "outcome", result.getClass().getSimpleName());
    return result;
  }

  /** Lock or Switch user: nobody operates the device until a PIN is entered (Figma 07). */
  public void end(String token, String reason) {
    Instant now = clock.now();
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          String sessionKey = key(token);
          Map<String, Object> active = database.queryOne(
              "SELECT user_id FROM iam.session_operators WHERE session_key = ? AND ended_at IS NULL",
              sessionKey);
          closeOpen(sessionKey, now, reason);
          database.update(
              "UPDATE iam.sessions SET operator_user_id = NULL, operator_since = NULL WHERE session_token = ?",
              token);
          if (active != null) {
            audit.record(AuditEntry.allowed(
                (UUID) active.get("user_id"), null, "iam:EndOperator",
                "wpt:iam:user:" + active.get("user_id"), reason));
          }
        });
  }

  /** The device's current operator, read with the session. */
  public Optional<Operator> operatorOf(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Map<String, Object> row =
        database.asModule(
            ModuleRole.IAM,
            null,
            () ->
                database.queryOne(
                    """
                    SELECT u.user_id, u.display_name, u.employee_code, s.operator_since
                    FROM iam.sessions s JOIN iam.users u ON u.user_id = s.operator_user_id
                    WHERE s.session_token = ? AND u.is_active
                    """,
                    token));
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(new Operator(
        (UUID) row.get("user_id"),
        (String) row.get("display_name"),
        Optional.ofNullable((String) row.get("employee_code")),
        ((Timestamp) row.get("operator_since")).toInstant()));
  }

  /** Sets a person's PIN and dock badge. From a trusted host or an administrator. */
  public UUID setPin(String email, String pin, String employeeCode) {
    PinPolicy.requireWellFormed(pin);
    if (employeeCode != null && !employeeCode.matches("[A-Z]{3}-[0-9]{5}")) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Employee code must look like LDR-00038");
    }
    String hash = hasher.hash(pin);
    String offline = verifier(pin);
    UUID userId =
        database.asModule(
            ModuleRole.IAM,
            null,
            () -> {
              Map<String, Object> user = database.queryOne(
                  "SELECT u.user_id FROM iam.users u JOIN iam.user_roles r ON r.user_id = u.user_id "
                      + "AND r.role_code = ? WHERE u.email = ? AND u.is_active",
                  LOADER_ROLE, email.trim().toLowerCase(java.util.Locale.ROOT));
              if (user == null) {
                return null;
              }
              UUID id = (UUID) user.get("user_id");
              int changed = database.update(
                  "UPDATE iam.users SET pin_hash = ?, pin_offline_verifier = ?,"
                      + " employee_code = coalesce(?, employee_code),"
                      + " row_version = row_version + 1, updated_at = now() WHERE user_id = ? AND is_active",
                  hash, offline, employeeCode, id);
              if (changed == 1) {
                audit.record(AuditEntry.allowed(Actor.SYSTEM_ID, null, "iam:SetPin", "wpt:iam:user:" + id,
                    "PIN provisioned through the trusted host command"));
                return id;
              }
              return null;
            });
    if (userId == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No such account");
    }
    return userId;
  }

  /**
   * Adds switches the device made offline to its operator history, in order, so
   * work queued under each operator syncs under them (R-IAM-20). Each is audited
   * as offline: the device checked the PIN, the server could not.
   *
   * @return the operator the device has after the last switch
   */
  public Optional<Operator> replayOffline(String token, SessionView device, List<Switch> switches) {
    requireLoaderDevice(device);
    Map<UUID, CrewMember> crew =
        crew(device).stream().collect(Collectors.toMap(CrewMember::userId, c -> c));
    Instant now = clock.now();
    String key = key(token);
    Optional<Operator> current =
        database.asModule(
            ModuleRole.IAM,
            null,
            () -> {
              // One replay per device at a time, or two could interleave history.
              database.queryOne("SELECT session_token FROM iam.sessions WHERE session_token = ? FOR UPDATE", token);
              Map<String, Object> last = database.queryOne(
                  "SELECT max(coalesce(ended_at, started_at)) AS at FROM iam.session_operators WHERE session_key = ?",
                  key);
              Optional<Instant> lastAt =
                  Optional.ofNullable(last == null ? null : (Timestamp) last.get("at")).map(Timestamp::toInstant);
              OfflineSwitchPolicy.requireReplayable(switches, Set.copyOf(crew.keySet()), lastAt, now);
              Optional<Operator> operator = Optional.empty();
              for (Switch s : switches) {
                closeOpen(key, s.at(), s.userId().isPresent() ? "switch" : "lock");
                if (s.userId().isEmpty()) {
                  operator = Optional.empty();
                  continue;
                }
                UUID userId = s.userId().get();
                CrewMember member = crew.get(userId);
                database.update(
                    "INSERT INTO iam.session_operators (session_key, user_id, started_at, offline)"
                        + " VALUES (?, ?, ?, true)",
                    key, userId, Timestamp.from(s.at()));
                audit.record(AuditEntry.allowed(userId, device.deviceId(), "iam:SwitchOperator",
                    "wpt:iam:user:" + userId, "switched offline at " + s.at() + "; PIN checked on the device"));
                operator = Optional.of(new Operator(
                    userId, member.displayName(), Optional.ofNullable(member.employeeCode()), s.at()));
              }
              database.update(
                  "UPDATE iam.sessions SET operator_user_id = ?, operator_since = ? WHERE session_token = ?",
                  operator.map(Operator::userId).orElse(null),
                  operator.map(o -> Timestamp.from(o.since())).orElse(null),
                  token);
              return operator;
            });
    metrics.increment("waypoint.operator.offline_switch");
    return current;
  }

  // ---- internals ---------------------------------------------------------------

  private String verifier(String pin) {
    byte[] salt = new byte[PinVerifier.SALT_BYTES];
    random.nextBytes(salt);
    return PinVerifier.create(pin, salt, PinVerifier.ITERATIONS);
  }

  private List<Instant> recentFailures(UUID userId, Instant now) {
    return database
        .query(
            """
            SELECT attempted_at FROM iam.pin_attempts
            WHERE user_id = ? AND NOT succeeded AND attempted_at > ?
              AND attempt_id > coalesce(
                  (SELECT max(attempt_id) FROM iam.pin_attempts WHERE user_id = ? AND succeeded),
                  0)
            ORDER BY attempt_id
            """,
            userId, Timestamp.from(now.minus(FAILURE_WINDOW)), userId)
        .stream()
        .map(r -> ((Timestamp) r.get("attempted_at")).toInstant())
        .toList();
  }

  private void closeOpen(String key, Instant now, String reason) {
    database.update(
        "UPDATE iam.session_operators SET ended_at = ?, end_reason = ? WHERE session_key = ? AND ended_at IS NULL",
        Timestamp.from(now), reason, key);
  }

  private static void requireLoaderDevice(SessionView device) {
    if (!device.roles().contains(LOADER_ROLE)) {
      throw new DomainException(
          ErrorCode.FORBIDDEN, "Switching operators is for shared loader devices", List.of("R-IAM-18"));
    }
  }

  static String key(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
