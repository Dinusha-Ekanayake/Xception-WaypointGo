package com.waypoint.dispatch.receipt.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus;
import com.waypoint.dispatch.receipt.domain.Receipt;
import com.waypoint.dispatch.receipt.domain.ReceiptLine;
import com.waypoint.dispatch.receipt.domain.ReceiptParameters;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Receipts in PostgreSQL. Always called inside a transaction someone else
 * opened, as {@code waypoint_receipt}, so row-level security has already
 * narrowed what any statement here can see or write.
 *
 * <p>Every update is {@code WHERE receipt_id = ? AND row_version = ?} and fails
 * on zero rows: a version column nobody checks is not concurrency control.
 */
@Repository
public class JdbcReceiptRepository {
  private static final String COLUMNS =
      """
      receipt_id, order_id, delivery_id, trip_id, outlet_id, depot_code, status, note, confirmed_by,
      confirmed_at, delivered_at, closes_at, late, delivered_by, delivered_units, row_version
      """;

  private final Database database;

  public JdbcReceiptRepository(Database database) {
    this.database = database;
  }

  /** A receipt and what Receipt recorded of the delivery that opened it. */
  public record Stored(Receipt receipt, Optional<UUID> deliveredBy, Optional<Integer> deliveredUnits) {}

  // ---- reads ---------------------------------------------------------------

  public Optional<Stored> findByOrder(UUID orderId) {
    return one("SELECT " + COLUMNS + " FROM receipt.confirmations WHERE order_id = ?", orderId);
  }

  public Optional<Stored> findByDelivery(UUID deliveryId) {
    return one("SELECT " + COLUMNS + " FROM receipt.confirmations WHERE delivery_id = ?", deliveryId);
  }

  public Optional<Stored> find(UUID receiptId) {
    return one("SELECT " + COLUMNS + " FROM receipt.confirmations WHERE receipt_id = ?", receiptId);
  }

  /** Waiting for the store, oldest delivery first. */
  public List<Receipt> pendingForOutlet(String outletId) {
    return many(
            "SELECT " + COLUMNS + " FROM receipt.confirmations"
                + " WHERE outlet_id = ? AND status = 'pending' ORDER BY delivered_at, receipt_id",
            outletId)
        .stream()
        .map(Stored::receipt)
        .toList();
  }

  /** Receipts whose window has passed with no answer, for the auto-close job (R-RCP-05). */
  public List<UUID> dueBy(Instant now) {
    List<UUID> ids = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT receipt_id FROM receipt.confirmations WHERE status = 'pending' AND closes_at <= ?"
                + " ORDER BY closes_at, receipt_id",
            Timestamp.from(now))) {
      ids.add((UUID) row.get("receipt_id"));
    }
    return ids;
  }

  public long pendingCount() {
    return ((Number)
            database.queryOne("SELECT count(*) AS n FROM receipt.confirmations WHERE status = 'pending'").get("n"))
        .longValue();
  }

  /** The parameters in force on {@code date}; an absent key refuses when it is read (POL-10). */
  public ReceiptParameters parameters(LocalDate date) {
    Map<String, BigDecimal> values = new HashMap<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT parameter_key, parameter_value FROM receipt.parameters"
                + " WHERE effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)",
            Date.valueOf(date),
            Date.valueOf(date))) {
      values.put((String) row.get("parameter_key"), (BigDecimal) row.get("parameter_value"));
    }
    return new ReceiptParameters(values);
  }

  public record HistoryEntry(
      Optional<ReceiptStatus> from, ReceiptStatus to, String reason, Optional<UUID> actorId, Instant at) {}

  public List<HistoryEntry> history(UUID receiptId) {
    List<HistoryEntry> out = new ArrayList<>();
    for (Map<String, Object> row :
        database.query(
            "SELECT from_status, to_status, reason, actor_id, occurred_at FROM receipt.confirmation_history"
                + " WHERE receipt_id = ? ORDER BY history_id",
            receiptId)) {
      out.add(
          new HistoryEntry(
              Optional.ofNullable((String) row.get("from_status")).map(JdbcReceiptRepository::status),
              status(row.get("to_status")),
              (String) row.get("reason"),
              Optional.ofNullable((UUID) row.get("actor_id")),
              instant(row.get("occurred_at"))));
    }
    return out;
  }

  // ---- writes --------------------------------------------------------------

  /** @return false when a receipt for this delivery already exists: a redelivered event */
  public boolean insert(Receipt r, Optional<UUID> deliveredBy, Optional<Integer> deliveredUnits, Instant at) {
    int inserted =
        database.update(
            """
            INSERT INTO receipt.confirmations
                (receipt_id, order_id, delivery_id, trip_id, outlet_id, depot_code, status, note, confirmed_by,
                 confirmed_at, delivered_at, closes_at, late, delivered_by, delivered_units, row_version,
                 created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT DO NOTHING
            """,
            r.receiptId(),
            r.orderId(),
            r.deliveryId(),
            r.tripId(),
            r.outletId(),
            r.depotCode(),
            code(r.status()),
            r.note().orElse(null),
            r.confirmedBy().orElse(null),
            r.confirmedAt().map(Timestamp::from).orElse(null),
            Timestamp.from(r.deliveredAt()),
            Timestamp.from(r.closesAt()),
            r.late(),
            deliveredBy.orElse(null),
            deliveredUnits.orElse(null),
            Timestamp.from(at),
            Timestamp.from(at));
    if (inserted == 0) {
      return false;
    }
    for (ReceiptLine line : r.lines()) {
      database.update(
          "INSERT INTO receipt.confirmation_lines (receipt_id, product_id, expected_quantity, received_quantity)"
              + " VALUES (?, ?, ?, ?)",
          r.receiptId(),
          line.productId(),
          line.expectedQuantity(),
          line.receivedQuantity().orElse(null));
    }
    return true;
  }

  /** Writes {@code next} over the row at {@code expectedVersion}; returns the new version. */
  public long update(Receipt next, long expectedVersion, Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE receipt.confirmations
           SET status = ?, note = ?, confirmed_by = ?, confirmed_at = ?, late = ?,
               row_version = row_version + 1, updated_at = ?
         WHERE receipt_id = ? AND row_version = ?
        """,
        code(next.status()),
        next.note().orElse(null),
        next.confirmedBy().orElse(null),
        next.confirmedAt().map(Timestamp::from).orElse(null),
        next.late(),
        Timestamp.from(at),
        next.receiptId(),
        expectedVersion);
    for (ReceiptLine line : next.lines()) {
      database.update(
          "UPDATE receipt.confirmation_lines SET received_quantity = ? WHERE receipt_id = ? AND product_id = ?",
          line.receivedQuantity().orElse(null),
          next.receiptId(),
          line.productId());
    }
    return expectedVersion + 1;
  }

  public void recordStatus(
      UUID receiptId,
      Optional<ReceiptStatus> from,
      ReceiptStatus to,
      String reason,
      UUID actorId,
      Optional<UUID> eventId,
      Instant at) {
    database.update(
        """
        INSERT INTO receipt.confirmation_history
            (receipt_id, from_status, to_status, reason, actor_id, event_id, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        receiptId,
        from.map(JdbcReceiptRepository::code).orElse(null),
        code(to),
        reason,
        actorId,
        eventId.orElse(null),
        Timestamp.from(at));
  }

  // ---- mapping -------------------------------------------------------------

  public static String code(ReceiptStatus status) {
    return status.name().toLowerCase(Locale.ROOT);
  }

  public static ReceiptStatus status(Object code) {
    return ReceiptStatus.valueOf(String.valueOf(code).toUpperCase(Locale.ROOT));
  }

  private Optional<Stored> one(String sql, Object... params) {
    List<Stored> found = many(sql, params);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  private List<Stored> many(String sql, Object... params) {
    List<Stored> out = new ArrayList<>();
    for (Map<String, Object> row : database.query(sql, params)) {
      out.add(
          new Stored(
              map(row),
              Optional.ofNullable((UUID) row.get("delivered_by")),
              Optional.ofNullable((Number) row.get("delivered_units")).map(Number::intValue)));
    }
    return out;
  }

  private Receipt map(Map<String, Object> row) {
    UUID receiptId = (UUID) row.get("receipt_id");
    List<ReceiptLine> lines = new ArrayList<>();
    for (Map<String, Object> line :
        database.query(
            "SELECT product_id, expected_quantity, received_quantity FROM receipt.confirmation_lines"
                + " WHERE receipt_id = ? ORDER BY product_id",
            receiptId)) {
      lines.add(
          new ReceiptLine(
              (String) line.get("product_id"),
              ((Number) line.get("expected_quantity")).intValue(),
              Optional.ofNullable((Number) line.get("received_quantity")).map(Number::intValue)));
    }
    return new Receipt(
        receiptId,
        (UUID) row.get("order_id"),
        (UUID) row.get("delivery_id"),
        (UUID) row.get("trip_id"),
        (String) row.get("outlet_id"),
        (String) row.get("depot_code"),
        status(row.get("status")),
        lines,
        Optional.ofNullable((String) row.get("note")),
        Optional.ofNullable((UUID) row.get("confirmed_by")),
        Optional.ofNullable(row.get("confirmed_at")).map(JdbcReceiptRepository::instant),
        instant(row.get("delivered_at")),
        instant(row.get("closes_at")),
        (Boolean) row.get("late"),
        ((Number) row.get("row_version")).longValue());
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp t) {
      return t.toInstant();
    }
    if (value instanceof OffsetDateTime o) {
      return o.toInstant();
    }
    throw new IllegalStateException("not a timestamp: " + value);
  }
}
