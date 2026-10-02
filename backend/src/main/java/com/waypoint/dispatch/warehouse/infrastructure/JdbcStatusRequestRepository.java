package com.waypoint.dispatch.warehouse.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code warehouse.status_requests}: cancel, ship and deliver calls owed to the
 * warehouse. Unique on (warehouse order, target), so a redelivered event asks
 * once.
 */
@Component
public class JdbcStatusRequestRepository {
  private final Database database;

  public JdbcStatusRequestRepository(Database database) {
    this.database = database;
  }

  public record StatusRequest(
      UUID requestId, UUID orderId, String warehouseOrderRef, String targetStatus, String cause,
      int attempts, long rowVersion) {}

  /** @return true when this is the first request for that target */
  public boolean request(
      UUID requestId, UUID orderId, String warehouseOrderRef, String target, String cause, Instant at) {
    return database.update(
            "INSERT INTO warehouse.status_requests (request_id, order_id, warehouse_order_ref,"
                + " target_status, cause, state, next_attempt_at, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?)"
                + " ON CONFLICT (warehouse_order_ref, target_status) DO NOTHING",
            requestId, orderId, warehouseOrderRef, target, cause,
            Timestamp.from(at), Timestamp.from(at), Timestamp.from(at))
        == 1;
  }

  public boolean wasRequested(String warehouseOrderRef, String target) {
    return database.queryOne(
            "SELECT 1 AS x FROM warehouse.status_requests WHERE warehouse_order_ref = ? AND target_status = ?",
            warehouseOrderRef, target)
        != null;
  }

  public List<StatusRequest> due(Instant now, int limit) {
    return database.query(
            "SELECT request_id, order_id, warehouse_order_ref, target_status, cause, attempts, row_version"
                + " FROM warehouse.status_requests WHERE state = 'pending' AND next_attempt_at <= ?"
                + " ORDER BY next_attempt_at LIMIT ?",
            Timestamp.from(now), limit)
        .stream()
        .map(JdbcStatusRequestRepository::map)
        .toList();
  }

  public void finish(StatusRequest r, String state, String error, Instant at) {
    database.updateExpectingOneRow(
        "UPDATE warehouse.status_requests SET state = ?, attempts = attempts + 1, last_error = ?,"
            + " updated_at = ?, row_version = row_version + 1 WHERE request_id = ? AND row_version = ?",
        state, error, Timestamp.from(at), r.requestId(), r.rowVersion());
  }

  public void retryAt(StatusRequest r, Instant next, String error, Instant at) {
    database.updateExpectingOneRow(
        "UPDATE warehouse.status_requests SET attempts = attempts + 1, next_attempt_at = ?,"
            + " last_error = ?, updated_at = ?, row_version = row_version + 1"
            + " WHERE request_id = ? AND row_version = ?",
        Timestamp.from(next), error, Timestamp.from(at), r.requestId(), r.rowVersion());
  }

  private static StatusRequest map(Map<String, Object> row) {
    return new StatusRequest(
        (UUID) row.get("request_id"),
        (UUID) row.get("order_id"),
        (String) row.get("warehouse_order_ref"),
        (String) row.get("target_status"),
        (String) row.get("cause"),
        ((Number) row.get("attempts")).intValue(),
        ((Number) row.get("row_version")).longValue());
  }
}
