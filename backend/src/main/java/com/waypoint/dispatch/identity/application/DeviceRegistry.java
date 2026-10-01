package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Cursor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The devices people sign in from.
 *
 * <p>A shared dock tablet is one device used by many people, and audit has to
 * tell them apart, so a device has an identity of its own that an administrator
 * grants. A client does not get one by claiming it: a sign-in naming an
 * unregistered device is refused.
 *
 * <p>A device is retired, never deleted, because audit rows and past sessions
 * name it. Retiring it ends the sessions opened from it in the same transaction.
 */
@Component
public class DeviceRegistry {
  private static final Set<String> KINDS =
      Set.of("shared_tablet", "personal_phone", "desktop", "terminal");

  private final Database database;
  private final AuditLog audit;
  private final SessionRegistry sessions;

  public DeviceRegistry(Database database, AuditLog audit, SessionRegistry sessions) {
    this.database = database;
    this.audit = audit;
    this.sessions = sessions;
  }

  /**
   * @param rowVersion what a caller sends back as {@code expectedVersion} to retire it
   */
  public record DeviceView(
      UUID deviceId,
      String label,
      String kind,
      String depotCode,
      boolean active,
      Instant registeredAt,
      Instant lastSeenAt,
      Instant retiredAt,
      long rowVersion) {}

  // ---- command bodies: a transaction is already open ----

  UUID applyRegister(Actor actor, String label, String kind, String depotCode) {
    if (!KINDS.contains(kind)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "kind is one of shared_tablet, personal_phone, desktop or terminal, not " + kind);
    }
    if (depotCode != null
        && database.queryOne(
                "SELECT d.depot_code FROM ref.depots d"
                    + " JOIN ref.reference_versions v"
                    + " ON v.reference_version_id = d.reference_version_id AND v.is_current"
                    + " WHERE d.depot_code = ?",
                depotCode)
            == null) {
      throw new DomainException(
          ErrorCode.NOT_FOUND, "No depot " + depotCode + " in the current reference version");
    }
    UUID deviceId = UUID.randomUUID();
    database.update(
        "INSERT INTO iam.devices (device_id, device_label, device_kind, depot_code, registered_by)"
            + " VALUES (?, ?, ?, ?, ?)",
        deviceId,
        label.trim(),
        kind,
        depotCode,
        actor.userId());
    record(actor, "iam:RegisterDevice", deviceId, kind + " registered");
    return deviceId;
  }

  /** @return how many sessions opened from the device were ended with it */
  int applyRetire(Actor actor, UUID deviceId, Long expectedVersion, Instant now) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT is_active, row_version FROM iam.devices WHERE device_id = ?", deviceId);
    if (row == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No device " + deviceId);
    }
    if (expectedVersion == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "expectedVersion is required to retire device " + deviceId);
    }
    long actual = ((Number) row.get("row_version")).longValue();
    if (actual != expectedVersion) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT,
          "Device " + deviceId + " is at version " + actual + ", not " + expectedVersion);
    }
    if (!Boolean.TRUE.equals(row.get("is_active"))) {
      throw new DomainException(ErrorCode.CONFLICT, "Device " + deviceId + " is already retired");
    }
    database.updateExpectingOneRow(
        "UPDATE iam.devices SET is_active = false, retired_at = ?, row_version = row_version + 1"
            + " WHERE device_id = ? AND row_version = ?",
        Timestamp.from(now),
        deviceId,
        actual);
    int revoked = sessions.revokeAllOn(deviceId);
    record(actor, "iam:RetireDevice", deviceId, "retired, " + revoked + " session(s) ended");
    return revoked;
  }

  // ---- reads ----

  /** Keyset paginated on the device id. Never OFFSET. */
  public Page<DeviceView> page(String after, Integer limit) {
    int size = Page.limit(limit);
    List<String> key = Cursor.decode(after, 1);
    UUID afterId = key.isEmpty() ? null : uuidOf(key.get(0));
    List<DeviceView> rows =
        database
            .asModule(
                ModuleRole.IAM,
                null,
                () ->
                    database.query(
                        """
                        SELECT device_id, device_label, device_kind, depot_code, is_active,
                               registered_at, last_seen_at, retired_at, row_version
                        FROM iam.devices
                        WHERE (?::uuid IS NULL OR device_id > ?::uuid)
                        ORDER BY device_id
                        LIMIT ?
                        """,
                        afterId,
                        afterId,
                        size + 1))
            .stream()
            .map(DeviceRegistry::toView)
            .toList();
    return Page.fromOverfetch(rows, size, device -> Cursor.encode(device.deviceId().toString()));
  }

  private static DeviceView toView(Map<String, Object> r) {
    return new DeviceView(
        (UUID) r.get("device_id"),
        (String) r.get("device_label"),
        (String) r.get("device_kind"),
        (String) r.get("depot_code"),
        (Boolean) r.get("is_active"),
        instant(r.get("registered_at")),
        instant(r.get("last_seen_at")),
        instant(r.get("retired_at")),
        ((Number) r.get("row_version")).longValue());
  }

  private static Instant instant(Object value) {
    return value == null ? null : ((Timestamp) value).toInstant();
  }

  private static UUID uuidOf(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw Cursor.invalid();
    }
  }

  private void record(Actor actor, String action, UUID deviceId, String reason) {
    audit.record(
        AuditEntry.allowed(
            actor.userId(), actor.deviceId(), action, "wpt:iam:device:" + deviceId, reason));
  }
}
