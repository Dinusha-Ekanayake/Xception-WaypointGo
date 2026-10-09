package com.waypoint.dispatch.platform.scheduling;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The size of the tables that grow every day (roadmap B-04, F-12), as
 * {@code waypoint.table.rows} and {@code waypoint.table.bytes} by table, so an
 * alert sees a retention job that stopped keeping up before the disk does.
 *
 * <p>Rows are the planner's estimate from {@code pg_class.reltuples}, which costs
 * nothing to read and is close enough to see growth; a {@code count(*)} on the
 * audit log would scan it every hour. A partitioned table is summed over its
 * partitions. Names are looked up in the catalogue, which every role may read,
 * so the job needs no grant on the other modules' schemas.
 */
@Component
public class TableSizeJob implements ScheduledJob {

  /** The tables that receive a row per command, event, position or job run. */
  static final List<String> WATCHED =
      List.of(
          "integration.audit_log",
          "integration.command_receipts",
          "integration.outbox_events",
          "integration.job_runs",
          "sync.operations",
          "execution.vehicle_positions",
          "notification.notifications",
          "messaging.messages");

  private final Database database;
  private final Map<String, AtomicLong> rows = new LinkedHashMap<>();
  private final Map<String, AtomicLong> bytes = new LinkedHashMap<>();

  public TableSizeJob(Database database, Metrics metrics) {
    this.database = database;
    for (String table : WATCHED) {
      AtomicLong rowCount = new AtomicLong(-1);
      AtomicLong byteCount = new AtomicLong(-1);
      rows.put(table, rowCount);
      bytes.put(table, byteCount);
      metrics.gauge("waypoint.table.rows", rowCount::get, "table", table);
      metrics.gauge("waypoint.table.bytes", byteCount::get, "table", table);
    }
  }

  @Override
  public String name() {
    return "platform.table-sizes";
  }

  @Override
  public String cron() {
    return "0 7 * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.INTEGRATION;
  }

  @Override
  public void run(Instant now) {
    for (Map<String, Object> row : sizes()) {
      String table = (String) row.get("name");
      rows.get(table).set(((Number) row.get("rows")).longValue());
      bytes.get(table).set(((Number) row.get("bytes")).longValue());
    }
  }

  /** One row per watched table that exists: the table and its partitions, summed. */
  List<Map<String, Object>> sizes() {
    return database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.query(
                "SELECT t.name, sum(greatest(c.reltuples, 0))::bigint AS rows,"
                    + " sum(pg_total_relation_size(c.oid))::bigint AS bytes"
                    + " FROM unnest(?::text[]) AS t(name)"
                    + " JOIN pg_namespace n ON n.nspname = split_part(t.name, '.', 1)"
                    + " JOIN pg_class p ON p.relnamespace = n.oid AND p.relname = split_part(t.name, '.', 2)"
                    + " JOIN pg_class c ON c.oid = p.oid"
                    + " OR c.oid IN (SELECT inhrelid FROM pg_inherits WHERE inhparent = p.oid)"
                    + " GROUP BY t.name",
                (Object) WATCHED.toArray(String[]::new)));
  }

  /** The last reading of a table's rows, or -1 before the first run. */
  long rowsOf(String table) {
    return rows.get(table).get();
  }

  long bytesOf(String table) {
    return bytes.get(table).get();
  }
}
