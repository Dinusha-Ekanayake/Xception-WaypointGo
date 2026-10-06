package com.waypoint.dispatch.issues.infrastructure;

import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Write-offs recorded by inquiries (FF-03). Never opens a transaction; row-level
 * security keeps them to the depots the actor is scoped to.
 */
@Repository
public class JdbcWriteOffs {
  private static final String COLUMNS =
      "write_off_id, issue_id, depot_code, outcome, value_lkr, tech_line, attachment_ids, recorded_by, recorded_at,"
          + " status, decided_by, decided_at, decision_note, row_version";

  private final Database database;

  public JdbcWriteOffs(Database database) {
    this.database = database;
  }

  public record Row(
      UUID writeOffId,
      UUID issueId,
      String depotCode,
      String outcome,
      BigDecimal valueLkr,
      boolean techLine,
      List<UUID> attachmentIds,
      UUID recordedBy,
      Instant recordedAt,
      String status,
      Optional<UUID> decidedBy,
      Optional<Instant> decidedAt,
      Optional<String> decisionNote,
      long rowVersion) {}

  public void insert(
      UUID writeOffId, UUID issueId, String depotCode, String outcome, BigDecimal valueLkr, boolean techLine,
      List<UUID> attachmentIds, UUID recordedBy, Instant at, UUID commandId) {
    database.update(
        "INSERT INTO issues.write_offs (write_off_id, issue_id, depot_code, outcome, value_lkr, tech_line, attachment_ids,"
            + " recorded_by, recorded_at, command_id) VALUES (?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?)",
        writeOffId, issueId, depotCode, outcome, valueLkr, techLine, uuidArray(attachmentIds), recordedBy,
        Timestamp.from(at), commandId);
  }

  public Optional<Row> find(UUID writeOffId) {
    return Optional.ofNullable(
            database.queryOne("SELECT " + COLUMNS + " FROM issues.write_offs WHERE write_off_id = ?", writeOffId))
        .map(JdbcWriteOffs::row);
  }

  public List<Row> ofIssue(UUID issueId) {
    return database
        .query("SELECT " + COLUMNS + " FROM issues.write_offs WHERE issue_id = ? ORDER BY recorded_at", issueId)
        .stream()
        .map(JdbcWriteOffs::row)
        .toList();
  }

  /** Decides a recorded write-off at the version the caller saw; zero rows when it moved on or was decided. */
  public int decide(UUID writeOffId, long expectedVersion, String status, UUID by, Instant at, String note) {
    return database.update(
        "UPDATE issues.write_offs SET status = ?, decided_by = ?, decided_at = ?, decision_note = ?,"
            + " row_version = row_version + 1 WHERE write_off_id = ? AND row_version = ? AND status = 'recorded'",
        status, by, Timestamp.from(at), note, writeOffId, expectedVersion);
  }

  private static String uuidArray(List<UUID> ids) {
    return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
  }

  private static Row row(Map<String, Object> r) {
    return new Row(
        (UUID) r.get("write_off_id"),
        (UUID) r.get("issue_id"),
        (String) r.get("depot_code"),
        (String) r.get("outcome"),
        (BigDecimal) r.get("value_lkr"),
        (Boolean) r.get("tech_line"),
        ids(r.get("attachment_ids")),
        (UUID) r.get("recorded_by"),
        ((Timestamp) r.get("recorded_at")).toInstant(),
        (String) r.get("status"),
        Optional.ofNullable((UUID) r.get("decided_by")),
        Optional.ofNullable((Timestamp) r.get("decided_at")).map(Timestamp::toInstant),
        Optional.ofNullable((String) r.get("decision_note")),
        ((Number) r.get("row_version")).longValue());
  }

  private static List<UUID> ids(Object value) {
    if (value == null) return List.of();
    try {
      Object array = value instanceof Array a ? a.getArray() : value;
      return Arrays.stream((Object[]) array).map(o -> o instanceof UUID u ? u : UUID.fromString(o.toString())).toList();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("could not read the write-off's photo ids", e);
    }
  }
}
