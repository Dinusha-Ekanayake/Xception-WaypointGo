package com.waypoint.dispatch.intelligence.infrastructure;

import com.waypoint.dispatch.intelligence.contract.KpiViews.KpiEntryView;
import com.waypoint.dispatch.platform.db.Database;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** ml.kpi_entries, append only, and the finished stops the app counts from (roadmap B-05). */
@Repository
public class JdbcKpiRepository {
  private final Database database;

  public JdbcKpiRepository(Database database) {
    this.database = database;
  }

  public void insert(UUID id, String depot, LocalDate weekStart, String kpi, BigDecimal value, String note, UUID by, Instant at) {
    database.update(
        "INSERT INTO ml.kpi_entries (entry_id, depot_code, week_start, kpi, value, note, entered_by, entered_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id, depot, Date.valueOf(weekStart), kpi, value, note, by, Timestamp.from(at));
  }

  /** The newest figure per week and KPI, for weeks starting in [from, to]. */
  public List<Row> current(String depot, LocalDate from, LocalDate to) {
    return database.query(
            "SELECT DISTINCT ON (week_start, kpi) week_start, kpi, value, note, entered_by, entered_at"
                + " FROM ml.kpi_entries WHERE depot_code = ? AND week_start BETWEEN ? AND ?"
                + " ORDER BY week_start, kpi, entered_at DESC",
            depot, Date.valueOf(from), Date.valueOf(to))
        .stream()
        .map(r -> new Row(
            ((Date) r.get("week_start")).toLocalDate(),
            (String) r.get("kpi"),
            new KpiEntryView(
                (BigDecimal) r.get("value"),
                Optional.ofNullable((String) r.get("note")),
                (UUID) r.get("entered_by"),
                ((Timestamp) r.get("entered_at")).toInstant())))
        .toList();
  }

  /** The depot's finished stops between two service dates, with the outlet to look its brand up by. */
  public List<Map<String, Object>> finishedStops(String depot, LocalDate from, LocalDate to) {
    return database.query(
        "SELECT service_date, outlet_id, outcome, late_minutes, arrived_at, label_source"
            + " FROM ml.delivery_outcomes WHERE depot_code = ? AND service_date BETWEEN ? AND ?",
        depot, Date.valueOf(from), Date.valueOf(to));
  }

  public record Row(LocalDate weekStart, String kpi, KpiEntryView entry) {}
}
