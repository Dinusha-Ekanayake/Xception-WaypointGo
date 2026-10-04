package com.waypoint.dispatch.receipt.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.receipt.domain.Handover;
import com.waypoint.dispatch.receipt.domain.Handover.State;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Handover PINs in PostgreSQL. Always called inside a transaction someone else
 * opened, as {@code waypoint_receipt}, so row-level security has already
 * narrowed what any statement here can see or write.
 *
 * <p>Every update is {@code WHERE receipt_id = ? AND row_version = ?} and fails
 * on zero rows. Two entries at once therefore cannot both be counted from the
 * same starting point, and the one that loses gets a conflict, which says nothing
 * about whether its PIN was right.
 */
@Repository
public class JdbcHandoverRepository {
  private static final String COLUMNS =
      """
      receipt_id, order_id, outlet_id, depot_code, vehicle_id, service_date, pin_salt, pin_hash, state, attempts,
      issued_at, expires_at, confirmed_at, confirmed_by, row_version
      """;

  private final Database database;

  public JdbcHandoverRepository(Database database) {
    this.database = database;
  }

  public Optional<Handover> findByOrder(UUID orderId) {
    Map<String, Object> row =
        database.queryOne("SELECT " + COLUMNS + " FROM receipt.handovers WHERE order_id = ?", orderId);
    return Optional.ofNullable(row).map(JdbcHandoverRepository::map);
  }

  /** @return false when this receipt already has one: an answer replayed */
  public boolean insert(Handover h, Instant at) {
    return database.update(
            """
            INSERT INTO receipt.handovers
                (receipt_id, order_id, outlet_id, depot_code, vehicle_id, service_date, pin_salt, pin_hash, state,
                 attempts, issued_at, expires_at, row_version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT DO NOTHING
            """,
            h.receiptId(), h.orderId(), h.outletId(), h.depotCode(), h.vehicleId(), Date.valueOf(h.serviceDate()),
            h.salt(), h.pinHash(), code(h.state()), h.attempts(), Timestamp.from(h.issuedAt()),
            Timestamp.from(h.expiresAt()), Timestamp.from(at), Timestamp.from(at))
        == 1;
  }

  /** Writes {@code next} over the row at {@code expectedVersion}; returns the new version. */
  public long update(Handover next, long expectedVersion, Instant at) {
    database.updateExpectingOneRow(
        """
        UPDATE receipt.handovers
           SET pin_salt = ?, pin_hash = ?, state = ?, attempts = ?, issued_at = ?, expires_at = ?,
               confirmed_at = ?, confirmed_by = ?, row_version = row_version + 1, updated_at = ?
         WHERE receipt_id = ? AND row_version = ?
        """,
        next.salt(), next.pinHash(), code(next.state()), next.attempts(), Timestamp.from(next.issuedAt()),
        Timestamp.from(next.expiresAt()), next.confirmedAt().map(Timestamp::from).orElse(null),
        next.confirmedBy().orElse(null), Timestamp.from(at), next.receiptId(), expectedVersion);
    return expectedVersion + 1;
  }

  public void record(UUID receiptId, String action, UUID actorId, Instant at) {
    database.update(
        "INSERT INTO receipt.handover_history (receipt_id, action, actor_id, occurred_at) VALUES (?, ?, ?, ?)",
        receiptId, action, actorId, Timestamp.from(at));
  }

  /** Whether the actor drives the vehicle on that date: the scope half of a driver's entry (R-IAM-13). */
  public boolean actorDrives(String vehicleId, java.time.LocalDate serviceDate) {
    return Boolean.TRUE.equals(
        database.queryOne("SELECT app.actor_drives(?, ?) AS ok", vehicleId, Date.valueOf(serviceDate)).get("ok"));
  }

  /** Whether the actor holds the outlet: the scope half of the store's reissue. */
  public boolean actorHasOutlet(String outletId) {
    return Boolean.TRUE.equals(database.queryOne("SELECT app.actor_has_outlet(?) AS ok", outletId).get("ok"));
  }

  // ---- mapping -------------------------------------------------------------

  static String code(State state) {
    return state.name().toLowerCase(Locale.ROOT);
  }

  private static Handover map(Map<String, Object> row) {
    return new Handover(
        (UUID) row.get("receipt_id"),
        (UUID) row.get("order_id"),
        (String) row.get("outlet_id"),
        (String) row.get("depot_code"),
        (String) row.get("vehicle_id"),
        ((Date) row.get("service_date")).toLocalDate(),
        (String) row.get("pin_salt"),
        (String) row.get("pin_hash"),
        State.valueOf(String.valueOf(row.get("state")).toUpperCase(Locale.ROOT)),
        ((Number) row.get("attempts")).intValue(),
        instant(row.get("issued_at")),
        instant(row.get("expires_at")),
        Optional.ofNullable(row.get("confirmed_at")).map(JdbcHandoverRepository::instant),
        Optional.ofNullable((UUID) row.get("confirmed_by")),
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
