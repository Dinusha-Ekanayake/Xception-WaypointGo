package com.waypoint.dispatch.platform.scheduling;

import com.waypoint.dispatch.platform.config.AuditProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the audit log writable (PLT-09) and bounded (PLT-10).
 *
 * <p>Every command commits its audit row with the change it describes, so a month
 * with no partition does not lose a row, it fails the command. This job creates
 * the current month and the next {@code partitionsAhead} every day, and raises
 * {@code waypoint.audit.partitions_short} when fewer than two future months
 * exist after it ran, which means it has been failing for a while.
 *
 * <p>Months older than the retention period are detached, never dropped: the data
 * stays in a table of its own for whoever archives it (P-14 is undecided). Both
 * operations are {@code SECURITY DEFINER} functions, because the application role
 * owns nothing and may not run DDL.
 */
@Component
public class AuditPartitionJob implements ScheduledJob {
  private static final Logger log = LoggerFactory.getLogger(AuditPartitionJob.class);

  private final Database database;
  private final AuditProperties properties;
  private final Metrics metrics;
  private final AtomicLong futureMonths = new AtomicLong(-1);

  public AuditPartitionJob(Database database, AuditProperties properties, Metrics metrics) {
    this.database = database;
    this.properties = properties;
    this.metrics = metrics;
    metrics.gauge("waypoint.audit.partitions_ahead", futureMonths::get);
  }

  @Override
  public String name() {
    return "platform.audit-partitions";
  }

  /** Daily and early, so a failure is seen long before it matters. */
  @Override
  public String cron() {
    return "0 15 2 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.INTEGRATION;
  }

  @Override
  public void run(Instant now) {
    for (LocalDate month : PartitionPlanner.required(now, properties.partitionsAhead())) {
      database.asSystem(
          ModuleRole.INTEGRATION,
          () ->
              database.queryOne(
                  "SELECT integration.ensure_audit_partition(?::date)", Date.valueOf(month)));
    }

    List<String> existing = partitions();
    int ahead = PartitionPlanner.futureCount(now, existing);
    futureMonths.set(ahead);
    if (ahead < 2) {
      metrics.increment("waypoint.audit.partitions_short");
      log.error(
          "Only {} future audit partition(s) exist. Commands fail once the last one ends.", ahead);
    }

    for (String name : PartitionPlanner.expired(now, properties.retentionMonths(), existing)) {
      boolean detached =
          database.asSystem(
              ModuleRole.INTEGRATION,
              () ->
                  Boolean.TRUE.equals(
                      database
                          .queryOne("SELECT integration.detach_audit_partition(?) AS done", name)
                          .get("done")));
      if (detached) {
        metrics.increment("waypoint.audit.partitions_detached");
        log.info(
            "Detached audit partition {} (older than {} months)",
            name,
            properties.retentionMonths());
      }
    }
  }

  /** The monthly partitions currently attached to the audit log. */
  List<String> partitions() {
    List<Map<String, Object>> rows =
        database.asSystem(
            ModuleRole.INTEGRATION,
            () ->
                database.query(
                    "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                        + " WHERE i.inhparent = 'integration.audit_log'::regclass"));
    return rows.stream().map(row -> (String) row.get("relname")).toList();
  }
}
