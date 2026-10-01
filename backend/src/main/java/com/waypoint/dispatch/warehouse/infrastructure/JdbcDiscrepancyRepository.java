package com.waypoint.dispatch.warehouse.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@code warehouse.discrepancies}. One open row per warehouse order and kind. */
@Component
public class JdbcDiscrepancyRepository {
  private final Database database;

  public JdbcDiscrepancyRepository(Database database) {
    this.database = database;
  }

  /** @return true when this is new, false when the same discrepancy is already open */
  public boolean raise(
      UUID discrepancyId, UUID orderId, String warehouseOrderRef, String kind,
      String waypointStatus, String warehouseStatus, String detail, Instant at) {
    return database.update(
            "INSERT INTO warehouse.discrepancies (discrepancy_id, order_id, warehouse_order_ref, kind,"
                + " waypoint_status, warehouse_status, detail, raised_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (warehouse_order_ref, kind) WHERE resolved_at IS NULL DO NOTHING",
            discrepancyId, orderId, warehouseOrderRef, kind, waypointStatus, warehouseStatus, detail,
            Timestamp.from(at))
        == 1;
  }
}
