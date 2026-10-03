package com.waypoint.dispatch.issues.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Photos of delivery problems in PostgreSQL. Always called inside a transaction
 * someone else opened, as {@code waypoint_issues}: row-level security decides
 * which rows exist for the actor, and the bytes table only for the process.
 */
@Repository
public class JdbcIssueAttachments {
  private final Database database;

  public JdbcIssueAttachments(Database database) {
    this.database = database;
  }

  /** A photo row as stored. */
  public record Stored(UUID attachmentId, UUID orderId, String outletId, String sha256, String contentType) {}

  public record NewAttachment(
      UUID attachmentId,
      String outletId,
      String depotCode,
      UUID orderId,
      Optional<UUID> receiptId,
      String contentType,
      int sizeBytes,
      String sha256,
      String storageKey,
      LocalDate retainUntil,
      UUID uploadedBy) {}

  public record Expired(UUID attachmentId, String storageKey) {}

  public Optional<Stored> find(UUID attachmentId) {
    Map<String, Object> row =
        database.queryOne(
            "SELECT attachment_id, order_id, outlet_id, sha256, content_type FROM issues.attachments"
                + " WHERE attachment_id = ?",
            attachmentId);
    return Optional.ofNullable(row)
        .map(
            r ->
                new Stored(
                    (UUID) r.get("attachment_id"), (UUID) r.get("order_id"), (String) r.get("outlet_id"),
                    (String) r.get("sha256"), (String) r.get("content_type")));
  }

  /** The bytes, as the process. A repeat of the same content under the same key changes nothing. */
  public void putContent(String storageKey, String contentType, byte[] content, Instant at) {
    database.update(
        """
        INSERT INTO issues.attachment_content (storage_key, content_type, size_bytes, content, stored_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (storage_key) DO NOTHING
        """,
        storageKey, contentType, content.length, content, Timestamp.from(at));
  }

  /** @return false when the id was already taken, by this outlet or another */
  public boolean insert(NewAttachment a, Instant at) {
    return database.update(
            """
            INSERT INTO issues.attachments
                (attachment_id, outlet_id, depot_code, order_id, receipt_id, content_type, size_bytes, sha256,
                 storage_key, retain_until, uploaded_by, uploaded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (attachment_id) DO NOTHING
            """,
            a.attachmentId(), a.outletId(), a.depotCode(), a.orderId(), a.receiptId().orElse(null),
            a.contentType(), a.sizeBytes(), a.sha256(), a.storageKey(), Date.valueOf(a.retainUntil()),
            a.uploadedBy(), Timestamp.from(at))
        == 1;
  }

  /** Names photos on an issue. Repeating a link changes nothing; a photo not here yet is linked by id. */
  public void link(UUID issueId, Collection<UUID> attachmentIds, Instant at) {
    for (UUID attachmentId : attachmentIds) {
      database.update(
          "INSERT INTO issues.issue_attachments (issue_id, attachment_id, linked_at) VALUES (?, ?, ?)"
              + " ON CONFLICT DO NOTHING",
          issueId, attachmentId, Timestamp.from(at));
    }
  }

  /** The photos taken while counting one receipt, which belong to its shortage investigation. */
  public List<UUID> ofReceipt(UUID receiptId) {
    return database
        .query("SELECT attachment_id FROM issues.attachments WHERE receipt_id = ? ORDER BY uploaded_at", receiptId)
        .stream()
        .map(r -> (UUID) r.get("attachment_id"))
        .toList();
  }

  /** Where a linked, visible, unpurged photo's bytes are, or null when the actor cannot see it. */
  public Map<String, Object> linked(UUID issueId, UUID attachmentId) {
    return database.queryOne(
        """
        SELECT a.storage_key, a.content_type
        FROM issues.issue_attachments l
        JOIN issues.attachments a ON a.attachment_id = l.attachment_id
        WHERE l.issue_id = ? AND l.attachment_id = ? AND a.purged_at IS NULL
        """,
        issueId,
        attachmentId);
  }

  /** The bytes, as the process; null once cleared past retention. */
  public byte[] content(String storageKey) {
    Map<String, Object> row =
        database.queryOne("SELECT content FROM issues.attachment_content WHERE storage_key = ?", storageKey);
    return row == null ? null : (byte[]) row.get("content");
  }

  // ---- retention (P-14) ------------------------------------------------------

  public List<Expired> expired(LocalDate today, int limit) {
    return database
        .query(
            "SELECT attachment_id, storage_key FROM issues.attachments"
                + " WHERE retain_until < ? AND purged_at IS NULL ORDER BY retain_until LIMIT ?",
            Date.valueOf(today), limit)
        .stream()
        .map(r -> new Expired((UUID) r.get("attachment_id"), (String) r.get("storage_key")))
        .toList();
  }

  /** Clears the bytes and marks the row; the row, its size and its hash stay on record. */
  public void purge(Expired e, Instant at) {
    database.update(
        "UPDATE issues.attachment_content SET content = NULL, purged_at = ? WHERE storage_key = ? AND purged_at IS NULL",
        Timestamp.from(at), e.storageKey());
    database.update(
        "UPDATE issues.attachments SET purged_at = ? WHERE attachment_id = ? AND purged_at IS NULL",
        Timestamp.from(at), e.attachmentId());
  }

  public long heldBytes() {
    return ((Number)
            database
                .queryOne(
                    "SELECT coalesce(sum(size_bytes), 0) AS n FROM issues.attachment_content WHERE purged_at IS NULL")
                .get("n"))
        .longValue();
  }
}
