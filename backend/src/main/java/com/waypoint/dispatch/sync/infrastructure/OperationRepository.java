package com.waypoint.dispatch.sync.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.sync.contract.SyncViews.OperationStatus;
import com.waypoint.dispatch.sync.contract.SyncViews.OperationView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code sync.operations}. Every method runs inside a transaction the application layer has
 * already opened as {@code waypoint_sync}, so row-level security limits it to the actor's own
 * operations.
 */
@Component
public class OperationRepository {
  private static final String COLUMNS =
      "operation_id, device_id, sequence, kind, status, problem_code, base_row_version,"
          + " received_at, applied_at, row_version";

  private final Database database;

  public OperationRepository(Database database) {
    this.database = database;
  }

  public Optional<OperationView> find(UUID operationId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT " + COLUMNS + " FROM sync.operations WHERE operation_id = ?", operationId))
        .map(OperationRepository::view);
  }

  public Optional<Long> rowVersion(UUID operationId) {
    return Optional.ofNullable(
            database.queryOne(
                "SELECT row_version FROM sync.operations WHERE operation_id = ?", operationId))
        .map(row -> ((Number) row.get("row_version")).longValue());
  }

  /**
   * False when the id is already taken. Row-level security hides another account's rows, so a
   * false here with nothing visible means someone else used this id.
   */
  public boolean insert(
      UUID operationId,
      UUID actorId,
      UUID deviceId,
      long sequence,
      String kind,
      String commandJson,
      Long baseRowVersion,
      Instant clientRecordedAt) {
    return database.update(
            """
            INSERT INTO sync.operations
                (operation_id, actor_id, device_id, sequence, kind, command,
                 base_row_version, client_recorded_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (operation_id) DO NOTHING
            """,
            operationId,
            actorId,
            deviceId,
            sequence,
            kind,
            commandJson,
            baseRowVersion,
            clientRecordedAt == null ? null : Timestamp.from(clientRecordedAt))
        == 1;
  }

  /** Only a RECEIVED operation moves; a settled one keeps the answer the device was first given. */
  public void settle(UUID operationId, OperationStatus status, String problemCode, String detail) {
    database.update(
        """
        UPDATE sync.operations
           SET status = ?, problem_code = ?, problem_detail = ?,
               applied_at = CASE WHEN ? = 'APPLIED' THEN now() END,
               row_version = row_version + 1
         WHERE operation_id = ? AND status = 'RECEIVED'
        """,
        status.name(),
        problemCode,
        detail,
        status.name(),
        operationId);
  }

  public void acknowledge(UUID operationId, long expectedVersion) {
    database.updateExpectingOneRow(
        """
        UPDATE sync.operations
           SET acknowledged_at = now(), row_version = row_version + 1
         WHERE operation_id = ? AND row_version = ?
        """,
        operationId,
        expectedVersion);
  }

  /** Keyset on (received_at, operation_id), never OFFSET. */
  public List<OperationView> after(Instant receivedAt, UUID operationId, int limit) {
    return database
        .query(
            "SELECT "
                + COLUMNS
                + " FROM sync.operations"
                + " WHERE (received_at, operation_id) > (?, ?)"
                + " ORDER BY received_at, operation_id LIMIT ?",
            Timestamp.from(receivedAt),
            operationId,
            limit)
        .stream()
        .map(OperationRepository::view)
        .toList();
  }

  public List<OperationView> pendingFor(UUID deviceId) {
    return list("device_id = ? AND status = 'RECEIVED' ORDER BY sequence", deviceId);
  }

  public List<OperationView> conflictsFor(UUID userId) {
    return list(
        "actor_id = ? AND status IN ('CONFLICT', 'REJECTED') ORDER BY received_at", userId);
  }

  private List<OperationView> list(String where, Object param) {
    return database
        .query("SELECT " + COLUMNS + " FROM sync.operations WHERE " + where, param)
        .stream()
        .map(OperationRepository::view)
        .toList();
  }

  private static OperationView view(Map<String, Object> row) {
    return new OperationView(
        (UUID) row.get("operation_id"),
        (UUID) row.get("device_id"),
        ((Number) row.get("sequence")).longValue(),
        (String) row.get("kind"),
        OperationStatus.valueOf((String) row.get("status")),
        Optional.ofNullable((String) row.get("problem_code")),
        Optional.ofNullable((Number) row.get("base_row_version")).map(Number::longValue),
        // The current version of the record belongs to the owning module, which this module may
        // not read. A reviewer fetches it from there.
        Optional.empty(),
        instant(row.get("received_at")),
        Optional.ofNullable(row.get("applied_at")).map(OperationRepository::instant));
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp t) {
      return t.toInstant();
    }
    if (value instanceof OffsetDateTime o) {
      return o.toInstant();
    }
    return (Instant) value;
  }
}
