package com.waypoint.dispatch.platform.storage;

import com.waypoint.dispatch.platform.config.ObjectStoreProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Moves the files stored before the object store was switched on (roadmap
 * M-01), a batch at a time, every module's table in turn.
 *
 * <p>Each file is written to the bucket with no transaction open, read back,
 * and compared byte for byte, and with the SHA-256 the row recorded when it was
 * uploaded; only then is its {@code object_key} set. A file that does not match
 * stays in the database and is counted. The database copy of a moved file is
 * cleared {@code S3_MOVE_AFTER} later, so a bad move can be undone in that time.
 * Running twice moves nothing twice.
 */
@Component
public class MediaBackfillJob implements ScheduledJob {
  static final int BATCH = 50;

  private final List<MediaTable> tables;
  private final S3Objects objects;
  private final Database database;
  private final ObjectStoreProperties properties;
  private final Metrics metrics;

  public MediaBackfillJob(
      List<MediaTable> tables, S3Objects objects, Database database, ObjectStoreProperties properties, Metrics metrics) {
    this.tables = List.copyOf(tables);
    this.objects = objects;
    this.database = database;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "storage.media-backfill";
  }

  /** Every ten minutes; quiet once everything has moved. */
  @Override
  public String cron() {
    return "0 */10 * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.INTEGRATION;
  }

  @Override
  public String bulkhead() {
    return "storage";
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many files were moved in this run */
  public int runAt(Instant now) {
    if (!objects.enabled()) return 0;
    int moved = 0;
    for (MediaTable t : tables) {
      moved += move(t, now);
      clear(t, now);
    }
    return moved;
  }

  private int move(MediaTable t, Instant now) {
    String sha = t.sha256Column() == null ? "NULL::text" : t.sha256Column();
    List<Map<String, Object>> rows = database.asSystem(t.moduleRole(), () -> database.query(
        "SELECT " + t.idColumn() + "::text AS id, " + sha + " AS sha256, content FROM " + t.table()
            + " WHERE object_key IS NULL AND content IS NOT NULL AND (" + t.liveCondition() + ") LIMIT ?",
        BATCH));
    int moved = 0;
    for (Map<String, Object> row : rows) {
      String id = (String) row.get("id");
      byte[] content = (byte[]) row.get("content");
      String key = t.prefix() + id;
      String expected = (String) row.get("sha256");
      try {
        if (expected != null && !expected.equalsIgnoreCase(sha256(content))) {
          metrics.increment("waypoint.storage.backfill", "table", t.table(), "outcome", "hash_mismatch_in_database");
          continue;
        }
        // No transaction is open: the upload never holds a database connection.
        objects.put(key, content, "application/octet-stream");
        byte[] back = objects.get(key).orElse(new byte[0]);
        if (!Arrays.equals(back, content)) {
          metrics.increment("waypoint.storage.backfill", "table", t.table(), "outcome", "read_back_mismatch");
          continue;
        }
        int updated = database.asSystem(t.moduleRole(), () -> database.update(
            "UPDATE " + t.table() + " SET object_key = ?, moved_at = ? WHERE " + t.idColumn() + "::text = ?"
                + " AND object_key IS NULL AND content IS NOT NULL",
            key, Timestamp.from(now), id));
        if (updated == 1) {
          moved++;
          metrics.increment("waypoint.storage.backfill", "table", t.table(), "outcome", "moved");
        }
      } catch (RuntimeException e) {
        metrics.increment("waypoint.storage.backfill", "table", t.table(), "outcome", "failed");
      }
    }
    return moved;
  }

  /** The database copies of files moved long enough ago. */
  private void clear(MediaTable t, Instant now) {
    Instant before = now.minus(properties.moveAfter());
    int cleared = database.asSystem(t.moduleRole(), () -> database.update(
        "UPDATE " + t.table() + " SET content = NULL WHERE object_key IS NOT NULL AND content IS NOT NULL"
            + " AND moved_at < ?",
        Timestamp.from(before)));
    if (cleared > 0) metrics.count("waypoint.storage.cleared", cleared, "table", t.table());
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
