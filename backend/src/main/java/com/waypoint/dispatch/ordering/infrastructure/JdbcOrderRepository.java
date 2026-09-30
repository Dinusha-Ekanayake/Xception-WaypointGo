package com.waypoint.dispatch.ordering.infrastructure;

import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Orders in PostgreSQL. Always called inside a transaction someone else opened,
 * as {@code waypoint_ordering}, so row-level security has already narrowed what
 * any statement here can see or write.
 *
 * <p>Every update is {@code WHERE order_id = ? AND row_version = ?} and fails on
 * zero rows: a version column nobody checks is not concurrency control.
 */
@Repository
public class JdbcOrderRepository {
  private static final String COLUMNS =
      """
      order_id, order_ref, outlet_id, depot_code, brand_code, district_name, requested_date,
      original_requested_date, delivery_date, status, warehouse_order_ref, temperature,
      weight_kg, volume_m3, item_count, line_revision, redelivery_of, trip_id,
      deferral_count, placed_by, placed_at, row_version
      """;

  private final Database database;

  public JdbcOrderRepository(Database database) {
    this.database = database;
  }

  // ---- reads ---------------------------------------------------------------

  public Optional<Order> find(UUID orderId) {
    return one("SELECT " + COLUMNS + " FROM ordering.orders WHERE order_id = ?", orderId);
  }

  /** An earlier attempt of the same command, found by its derived reference (R-STK-11). */
  public Optional<Order> findByRef(String orderRef) {
    return one("SELECT " + COLUMNS + " FROM ordering.orders WHERE order_ref = ?", orderRef);
  }

  public Optional<Order> findBySourceIssue(UUID issueId) {
    return one("SELECT " + COLUMNS + " FROM ordering.orders WHERE source_issue_id = ?", issueId);
  }

  public List<Order> ofTrip(UUID tripId) {
    return many(
        "SELECT " + COLUMNS + " FROM ordering.orders WHERE trip_id = ? ORDER BY order_ref", tripId);
  }

  /**
   * Orders due on or before {@code serviceDate} that the warehouse has still not
   * reserved, for the cutoff (R-STK-06). Includes those an earlier cutoff already
   * deferred, so an unreserved order keeps moving rather than sitting behind a
   * date that has passed; "on or before" lets a missed run catch up.
   */
  public List<Order> unreservedDueBy(LocalDate serviceDate) {
    return many(
        "SELECT " + COLUMNS + " FROM ordering.orders"
            + " WHERE delivery_date <= ? AND warehouse_order_ref IS NULL"
            + " AND status IN ('stock_unknown','deferred') ORDER BY delivery_date, order_ref",
        Date.valueOf(serviceDate));
  }

  /** Every live order for a depot and day, in the order Planning should see them. */
  public List<Order> dueOn(String depotCode, LocalDate serviceDate) {
    return many(
        "SELECT " + COLUMNS + " FROM ordering.orders"
            + " WHERE depot_code = ? AND delivery_date = ? AND status <> 'cancelled'"
            + " ORDER BY original_requested_date, order_ref",
        depotCode,
        Date.valueOf(serviceDate));
  }

  public boolean isClosed(String depotCode, LocalDate serviceDate) {
    return database.queryOne(
            "SELECT 1 FROM ordering.day_closures WHERE depot_code = ? AND service_date = ?",
            depotCode,
            Date.valueOf(serviceDate))
        != null;
  }

  /** An order as stored, with the facts the aggregate does not need but a reader does. */
  public record Stored(Order order, Instant placedAt) {}

  public Optional<Stored> findStored(UUID orderId) {
    List<Stored> found =
        stored("SELECT " + COLUMNS + " FROM ordering.orders WHERE order_id = ?", orderId);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  /** Newest first, on the keyset {@code (placed_at, order_id)}; never OFFSET. */
  public List<Stored> pageForOutlet(
      String outletId, Optional<Instant> beforePlacedAt, Optional<UUID> beforeId, int limit) {
    if (beforePlacedAt.isEmpty()) {
      return stored(
          "SELECT " + COLUMNS + " FROM ordering.orders WHERE outlet_id = ?"
              + " ORDER BY placed_at DESC, order_id DESC LIMIT ?",
          outletId,
          limit);
    }
    return stored(
        "SELECT " + COLUMNS + " FROM ordering.orders WHERE outlet_id = ?"
            + " AND (placed_at, order_id) < (?, ?)"
            + " ORDER BY placed_at DESC, order_id DESC LIMIT ?",
        outletId,
        Timestamp.from(beforePlacedAt.get()),
        beforeId.orElseThrow(),
        limit);
  }

  /**
   * Demand Planning may allocate: confirmed or deferred, due that day, and
   * actually reserved. A deferred order with no reservation is one the cutoff
   * carried forward while stock was unknown, and stock is never assumed (R-STK-05).
   */
  public List<Order> demand(String depotCode, LocalDate serviceDate) {
    return many(
        "SELECT " + COLUMNS + " FROM ordering.orders"
            + " WHERE depot_code = ? AND delivery_date = ?"
            + " AND status IN ('confirmed','deferred') AND warehouse_order_ref IS NOT NULL"
            + " ORDER BY original_requested_date, order_ref",
        depotCode,
        Date.valueOf(serviceDate));
  }

  public record StatusChange(
      Optional<OrderStatus> from, OrderStatus to, String reason, Optional<UUID> actorId, Instant at) {}

  /** Oldest first. */
  public List<StatusChange> history(UUID orderId) {
    List<StatusChange> changes = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT from_status, to_status, reason, actor_id, occurred_at"
                + " FROM ordering.order_status_history WHERE order_id = ? ORDER BY history_id",
            orderId)) {
      changes.add(
          new StatusChange(
              Optional.ofNullable(row.get("from_status")).map(JdbcOrderRepository::status),
              status(row.get("to_status")),
              (String) row.get("reason"),
              Optional.ofNullable((UUID) row.get("actor_id")),
              ((Timestamp) row.get("occurred_at")).toInstant()));
    }
    return changes;
  }

  // ---- writes --------------------------------------------------------------

  public void insert(
      Order order, UUID placedBy, Instant at, UUID commandId, Optional<UUID> sourceIssueId) {
    Optional<Reservation> r = order.reservation();
    database.update(
        """
        INSERT INTO ordering.orders
            (order_id, order_ref, outlet_id, depot_code, brand_code, district_name,
             requested_date, original_requested_date, delivery_date, status,
             warehouse_order_ref, temperature, weight_kg, volume_m3, item_count,
             redelivery_of, source_issue_id, trip_id, deferral_count, placed_by, placed_at,
             command_id, row_version, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
        """,
        order.orderId(),
        order.orderRef(),
        order.outletId(),
        order.depotCode(),
        order.brandCode(),
        order.districtName(),
        Date.valueOf(order.requestedDate()),
        Date.valueOf(order.originalRequestedDate()),
        Date.valueOf(order.deliveryDate()),
        code(order.status()),
        r.map(Reservation::warehouseOrderRef).orElse(null),
        r.map(Reservation::temperature).orElse(null),
        r.map(Reservation::weightKg).orElse(null),
        r.map(Reservation::volumeM3).orElse(null),
        r.map(Reservation::itemCount).orElse(null),
        order.redeliveryOf().orElse(null),
        sourceIssueId.orElse(null),
        order.tripId().orElse(null),
        order.deferralCount(),
        placedBy,
        Timestamp.from(at),
        commandId,
        Timestamp.from(at));
    insertLines(order.orderId(), 1, order.lines());
  }

  /**
   * Writes {@code next} over the row at {@code expectedVersion}.
   *
   * @param linesChanged true when {@code next} carries new lines, which become a
   *     new revision rather than replacing the old ones
   * @return the new row version
   */
  public long update(Order next, long expectedVersion, boolean linesChanged, Instant at) {
    Optional<Reservation> r = next.reservation();
    database.updateExpectingOneRow(
        """
        UPDATE ordering.orders
           SET delivery_date = ?, status = ?, warehouse_order_ref = ?, temperature = ?,
               weight_kg = ?, volume_m3 = ?, item_count = ?, trip_id = ?, deferral_count = ?,
               line_revision = line_revision + ?, row_version = row_version + 1, updated_at = ?
         WHERE order_id = ? AND row_version = ?
        """,
        Date.valueOf(next.deliveryDate()),
        code(next.status()),
        r.map(Reservation::warehouseOrderRef).orElse(null),
        r.map(Reservation::temperature).orElse(null),
        r.map(Reservation::weightKg).orElse(null),
        r.map(Reservation::volumeM3).orElse(null),
        r.map(Reservation::itemCount).orElse(null),
        next.tripId().orElse(null),
        next.deferralCount(),
        linesChanged ? 1 : 0,
        Timestamp.from(at),
        next.orderId(),
        expectedVersion);
    if (linesChanged) {
      Map<String, Object> row =
          database.queryOne(
              "SELECT line_revision FROM ordering.orders WHERE order_id = ?", next.orderId());
      insertLines(next.orderId(), ((Number) row.get("line_revision")).intValue(), next.lines());
    }
    return expectedVersion + 1;
  }

  public void recordStatus(
      UUID orderId,
      Optional<OrderStatus> from,
      OrderStatus to,
      String reason,
      UUID actorId,
      Optional<UUID> eventId,
      Instant at) {
    database.update(
        """
        INSERT INTO ordering.order_status_history
            (order_id, from_status, to_status, reason, actor_id, event_id, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        orderId,
        from.map(JdbcOrderRepository::code).orElse(null),
        code(to),
        reason,
        actorId,
        eventId.orElse(null),
        Timestamp.from(at));
  }

  public void close(String depotCode, LocalDate serviceDate, UUID closedBy, int orderCount, Instant at) {
    database.update(
        """
        INSERT INTO ordering.day_closures (depot_code, service_date, closed_at, closed_by, order_count)
        VALUES (?, ?, ?, ?, ?)
        """,
        depotCode,
        Date.valueOf(serviceDate),
        Timestamp.from(at),
        closedBy,
        orderCount);
  }

  // ---- mapping -------------------------------------------------------------

  public static String code(OrderStatus status) {
    return status.name().toLowerCase(Locale.ROOT);
  }

  public static OrderStatus status(Object code) {
    return OrderStatus.valueOf(String.valueOf(code).toUpperCase(Locale.ROOT));
  }

  private void insertLines(UUID orderId, int revision, List<OrderLine> lines) {
    for (OrderLine line : lines) {
      database.update(
          "INSERT INTO ordering.order_lines (order_id, revision, product_id, quantity)"
              + " VALUES (?, ?, ?, ?)",
          orderId,
          revision,
          line.productId(),
          line.quantity());
    }
  }

  private Optional<Order> one(String sql, Object... params) {
    List<Order> found = many(sql, params);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  private List<Order> many(String sql, Object... params) {
    return stored(sql, params).stream().map(Stored::order).toList();
  }

  private List<Stored> stored(String sql, Object... params) {
    List<Stored> orders = new ArrayList<>();
    for (Map<String, Object> row : database.query(sql, params)) {
      orders.add(new Stored(map(row), ((Timestamp) row.get("placed_at")).toInstant()));
    }
    return orders;
  }

  private Order map(Map<String, Object> row) {
    UUID orderId = (UUID) row.get("order_id");
    Optional<Reservation> reservation =
        row.get("warehouse_order_ref") == null
            ? Optional.empty()
            : Optional.of(
                new Reservation(
                    (String) row.get("warehouse_order_ref"),
                    (BigDecimal) row.get("weight_kg"),
                    (BigDecimal) row.get("volume_m3"),
                    (String) row.get("temperature"),
                    ((Number) row.get("item_count")).intValue()));
    List<OrderLine> lines = new ArrayList<>();
    for (Map<String, Object> line :
        database.query(
            "SELECT product_id, quantity FROM ordering.order_lines"
                + " WHERE order_id = ? AND revision = ? ORDER BY product_id",
            orderId,
            row.get("line_revision"))) {
      lines.add(
          new OrderLine((String) line.get("product_id"), ((Number) line.get("quantity")).intValue()));
    }
    return new Order(
        orderId,
        (String) row.get("order_ref"),
        (String) row.get("outlet_id"),
        (String) row.get("depot_code"),
        (String) row.get("brand_code"),
        (String) row.get("district_name"),
        date(row.get("requested_date")),
        date(row.get("original_requested_date")),
        date(row.get("delivery_date")),
        status(row.get("status")),
        reservation,
        Optional.ofNullable((UUID) row.get("redelivery_of")),
        Optional.ofNullable((UUID) row.get("trip_id")),
        ((Number) row.get("deferral_count")).intValue(),
        lines,
        ((Number) row.get("row_version")).longValue());
  }

  private static LocalDate date(Object value) {
    return ((Date) value).toLocalDate();
  }
}
