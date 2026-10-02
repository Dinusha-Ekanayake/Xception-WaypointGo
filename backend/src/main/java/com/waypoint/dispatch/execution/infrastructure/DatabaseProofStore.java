package com.waypoint.dispatch.execution.infrastructure;

import com.waypoint.dispatch.execution.domain.ProofStore;
import com.waypoint.dispatch.platform.config.ExecutionProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Proof artifacts in the database, in {@code execution.proof_content}
 * (decision 2026-10-01, issue #12). The default store: evidence lives beside
 * the records it supports, in the store every deployment already backs up,
 * instead of on one host's disk.
 *
 * <p>Each call is its own transaction, as the process: an upload has already
 * been authorized against its delivery, and a read against its signed link.
 * {@link #put} returns only once the row is committed, so a stored artifact is
 * durable; if the database is down it throws and the driver is told (EXE-10).
 *
 * <p>Artifacts written to {@code PROOF_DIR} before this store became the default
 * are still read from there, so switching loses no evidence.
 */
@Component
@ConditionalOnProperty(name = "app.execution.proof-store", havingValue = "database", matchIfMissing = true)
public class DatabaseProofStore implements ProofStore {
  private final Database database;
  private final Clock clock;
  private final Optional<LocalProofStore> earlier;

  public DatabaseProofStore(Database database, Clock clock, ExecutionProperties properties) {
    this.database = database;
    this.clock = clock;
    this.earlier =
        Files.isDirectory(Path.of(properties.proofDir()))
            ? Optional.of(new LocalProofStore(properties))
            : Optional.empty();
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    database.asModule(
        ModuleRole.EXECUTION,
        Actor.SYSTEM_ID,
        () -> {
          database.update(
              """
              INSERT INTO execution.proof_content (storage_key, content_type, size_bytes, content, stored_at)
              VALUES (?, ?, ?, ?, ?)
              ON CONFLICT (storage_key) DO UPDATE
                 SET content_type = EXCLUDED.content_type, size_bytes = EXCLUDED.size_bytes,
                     content = EXCLUDED.content, stored_at = EXCLUDED.stored_at, purged_at = NULL
              """,
              key, contentType, content.length, content, Timestamp.from(clock.now()));
        });
  }

  @Override
  public Optional<byte[]> get(String key) {
    Map<String, Object> row =
        database.readAs(
            ModuleRole.EXECUTION,
            Actor.SYSTEM_ID,
            () -> database.queryOne(
                "SELECT content FROM execution.proof_content WHERE storage_key = ?", key));
    if (row != null) {
      // A purged artifact is gone on purpose; it is not looked for elsewhere.
      return Optional.ofNullable((byte[]) row.get("content"));
    }
    return earlier.flatMap(store -> store.get(key));
  }

  @Override
  public void purge(String key) {
    database.asModule(
        ModuleRole.EXECUTION,
        Actor.SYSTEM_ID,
        () -> {
          database.update(
              "UPDATE execution.proof_content SET content = NULL, purged_at = ?"
                  + " WHERE storage_key = ? AND purged_at IS NULL",
              Timestamp.from(clock.now()), key);
        });
    earlier.ifPresent(store -> store.purge(key));
  }
}
