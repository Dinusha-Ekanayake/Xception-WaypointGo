package com.waypoint.dispatch.identity.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Profile photos (FF-07) and the scope facts deciding who sees one. Never opens
 * a transaction; Identity's role sees every row of its own tables.
 */
@Repository
public class JdbcProfilePhotos {
  private final Database database;

  public JdbcProfilePhotos(Database database) {
    this.database = database;
  }

  /** One stored photo. {@code content} is null when the row was read without its image. */
  public record Photo(
      UUID photoId,
      UUID userId,
      String contentType,
      int sizeBytes,
      String sha256,
      Instant uploadedAt,
      boolean removed,
      long rowVersion,
      byte[] content) {}

  public Optional<Photo> byId(UUID photoId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT photo_id, user_id, content_type, size_bytes, sha256, uploaded_at, removed_at, row_version,"
                    + " NULL::bytea AS content FROM iam.profile_photos WHERE photo_id = ?",
                photoId))
        .map(JdbcProfilePhotos::photo);
  }

  /** The person's current photo with its image. */
  public Optional<Photo> current(UUID userId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT photo_id, user_id, content_type, size_bytes, sha256, uploaded_at, removed_at, row_version,"
                    + " content FROM iam.profile_photos WHERE user_id = ? AND removed_at IS NULL",
                userId))
        .map(JdbcProfilePhotos::photo);
  }

  /** How many photos the person ever had: the expected version of the next upload. */
  public long version(UUID userId) {
    return ((Number)
            database.queryOne("SELECT count(*) AS n FROM iam.profile_photos WHERE user_id = ?", userId).get("n"))
        .longValue();
  }

  /** Every person who ever had a photo, with the current one when there is one; never the image. */
  public List<Map<String, Object>> statuses() {
    return database.query(
        """
        SELECT v.user_id, v.version, c.photo_id, c.uploaded_at, c.size_bytes, c.row_version
        FROM (SELECT user_id, count(*) AS version FROM iam.profile_photos GROUP BY user_id) v
        LEFT JOIN iam.profile_photos c ON c.user_id = v.user_id AND c.removed_at IS NULL
        ORDER BY v.user_id
        """);
  }

  public void insert(
      UUID photoId, UUID userId, byte[] content, String contentType, String sha256, String consent, UUID actor,
      Instant now) {
    database.update(
        """
        INSERT INTO iam.profile_photos
            (photo_id, user_id, content, content_type, size_bytes, sha256, consent_note, uploaded_by, uploaded_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        photoId, userId, content, contentType, content.length, sha256, consent, actor, Timestamp.from(now));
  }

  /** Deletes the image and keeps the record of who removed it, when and why. */
  public void remove(UUID photoId, long expectedRowVersion, UUID actor, Instant now, String reason) {
    database.updateExpectingOneRow(
        "UPDATE iam.profile_photos SET content = NULL, removed_at = ?, removed_by = ?, removal_reason = ?,"
            + " row_version = row_version + 1 WHERE photo_id = ? AND row_version = ? AND removed_at IS NULL",
        Timestamp.from(now), actor, reason, photoId, expectedRowVersion);
  }

  /** Replacing a photo removes the one before, image and all. */
  public void removeCurrent(UUID userId, UUID actor, Instant now, String reason) {
    database.update(
        "UPDATE iam.profile_photos SET content = NULL, removed_at = ?, removed_by = ?, removal_reason = ?,"
            + " row_version = row_version + 1 WHERE user_id = ? AND removed_at IS NULL",
        Timestamp.from(now), actor, reason, userId);
  }

  public boolean isAdministrator(UUID userId) {
    return database.queryOne(
            "SELECT 1 FROM iam.user_roles WHERE user_id = ? AND role_code = 'admin'", userId) != null;
  }

  public boolean exists(UUID userId) {
    return database.queryOne("SELECT 1 FROM iam.users WHERE user_id = ?", userId) != null;
  }

  public Set<String> depotsOf(UUID userId) {
    Set<String> depots = new HashSet<>();
    database.query("SELECT depot_code FROM iam.user_depot_access WHERE user_id = ?", userId)
        .forEach(r -> depots.add((String) r.get("depot_code")));
    return depots;
  }

  /** The vehicles the person is assigned to drive on a day. */
  public List<String> vehiclesOn(UUID userId, LocalDate day) {
    return database.query(
            "SELECT vehicle_id FROM iam.vehicle_driver_assignments WHERE driver_user_id = ? AND validity @> ?::date",
            userId, java.sql.Date.valueOf(day))
        .stream().map(r -> (String) r.get("vehicle_id")).toList();
  }

  private static Photo photo(Map<String, Object> row) {
    return new Photo(
        (UUID) row.get("photo_id"),
        (UUID) row.get("user_id"),
        (String) row.get("content_type"),
        ((Number) row.get("size_bytes")).intValue(),
        (String) row.get("sha256"),
        ((Timestamp) row.get("uploaded_at")).toInstant(),
        row.get("removed_at") != null,
        ((Number) row.get("row_version")).longValue(),
        (byte[]) row.get("content"));
  }
}
