package com.waypoint.dispatch.execution.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.storage.MediaTable;
import com.waypoint.dispatch.platform.storage.S3Objects;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The Fuel Pass images (FF-06b). Never opens a transaction; row-level security
 * decides which rows the actor sees: an administrator all of them, a driver the
 * vehicle they drive today or tomorrow.
 */
@Repository
public class JdbcFuelPasses {
  /** Where Fuel Pass images live in the object store (M-01). */
  public static final String PREFIX = "fuel-passes/";

  private final Database database;
  private final S3Objects objects;

  public JdbcFuelPasses(Database database, S3Objects objects) {
    this.database = database;
    this.objects = objects;
  }

  /** The passes' table, for the object store backfill (M-01). A revoked pass is kept as it was. */
  @org.springframework.stereotype.Component
  static class Media implements MediaTable {
    @Override public String table() { return "execution.fuel_passes"; }
    @Override public ModuleRole moduleRole() { return ModuleRole.EXECUTION; }
    @Override public String idColumn() { return "pass_id"; }
    @Override public String prefix() { return PREFIX; }
    @Override public String liveCondition() { return "TRUE"; }
  }

  /** One stored pass. {@code content} is empty when the caller asked for the row without the image. */
  public record Pass(
      UUID passId,
      String vehicleId,
      String contentType,
      int sizeBytes,
      String sha256,
      Instant uploadedAt,
      boolean revoked,
      long rowVersion,
      byte[] content) {}

  public Optional<Pass> byId(UUID passId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT pass_id, vehicle_id, content_type, size_bytes, sha256, uploaded_at, revoked_at, row_version,"
                    + " NULL::bytea AS content FROM execution.fuel_passes WHERE pass_id = ?",
                passId))
        .map(JdbcFuelPasses::pass);
  }

  /** The vehicle's current pass with its image. */
  public Optional<Pass> current(String vehicleId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT pass_id, vehicle_id, content_type, size_bytes, sha256, uploaded_at, revoked_at, row_version,"
                    + " content, object_key FROM execution.fuel_passes WHERE vehicle_id = ? AND revoked_at IS NULL",
                vehicleId))
        .map(row -> {
          row.put("content", objects.read((byte[]) row.get("content"), (String) row.get("object_key")));
          return pass(row);
        });
  }

  /** How many passes the vehicle ever had: the expected version of its next upload. */
  public long version(String vehicleId) {
    return ((Number)
            database.queryOne("SELECT count(*) AS n FROM execution.fuel_passes WHERE vehicle_id = ?", vehicleId).get("n"))
        .longValue();
  }

  /** Every vehicle that ever had a pass, with its current one when there is one; never the image. */
  public List<Map<String, Object>> statuses() {
    return database.query(
        """
        SELECT v.vehicle_id, v.version, c.pass_id, c.uploaded_at, c.size_bytes, c.row_version
        FROM (SELECT vehicle_id, count(*) AS version FROM execution.fuel_passes GROUP BY vehicle_id) v
        LEFT JOIN execution.fuel_passes c ON c.vehicle_id = v.vehicle_id AND c.revoked_at IS NULL
        ORDER BY v.vehicle_id
        """);
  }

  public void insert(
      UUID passId, String vehicleId, byte[] content, String contentType, String sha256, UUID actor, Instant now) {
    // M-01: with the object store on, the image goes to the bucket first and the row keeps where.
    String objectKey = null;
    if (objects.enabled()) {
      objectKey = PREFIX + passId;
      objects.put(objectKey, content, contentType);
    }
    database.update(
        """
        INSERT INTO execution.fuel_passes
            (pass_id, vehicle_id, content, object_key, content_type, size_bytes, sha256, uploaded_by, uploaded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        passId, vehicleId, objectKey == null ? content : null, objectKey, contentType, content.length, sha256, actor,
        Timestamp.from(now));
  }

  /** Revokes whatever pass is current for the vehicle; zero rows when there is none. */
  public int revokeCurrent(String vehicleId, UUID actor, Instant now, String reason) {
    return database.update(
        "UPDATE execution.fuel_passes SET revoked_at = ?, revoked_by = ?, revoke_reason = ?, row_version = row_version + 1"
            + " WHERE vehicle_id = ? AND revoked_at IS NULL",
        Timestamp.from(now), actor, reason, vehicleId);
  }

  /** Revokes one pass at the version the caller saw; zero rows when it moved on or was already revoked. */
  public int revoke(UUID passId, long expectedVersion, UUID actor, Instant now, String reason) {
    return database.update(
        "UPDATE execution.fuel_passes SET revoked_at = ?, revoked_by = ?, revoke_reason = ?, row_version = row_version + 1"
            + " WHERE pass_id = ? AND row_version = ? AND revoked_at IS NULL",
        Timestamp.from(now), actor, reason, passId, expectedVersion);
  }

  private static Pass pass(Map<String, Object> row) {
    return new Pass(
        (UUID) row.get("pass_id"),
        (String) row.get("vehicle_id"),
        (String) row.get("content_type"),
        ((Number) row.get("size_bytes")).intValue(),
        (String) row.get("sha256"),
        ((Timestamp) row.get("uploaded_at")).toInstant(),
        row.get("revoked_at") != null,
        ((Number) row.get("row_version")).longValue(),
        (byte[]) row.get("content"));
  }
}
