package com.waypoint.dispatch.platform.scheduling;

import com.waypoint.dispatch.platform.config.RetentionProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Removes the platform's own bookkeeping once it has outlived its use (issue #6).
 *
 * <ul>
 *   <li>command receipts past the replay window: a receipt older than any client
 *       could still replay answers nobody
 *   <li>published outbox events and their attempt history. A {@code dead} event is
 *       never purged: it is waiting for an administrator
 *   <li>consumer inbox rows, kept longer than the outbox rows they guard
 *   <li>records of scheduled job runs
 * </ul>
 *
 * <p>The audit log is not here: it ages out by detaching a partition
 * ({@link AuditPartitionJob}). Each table is deleted in bounded batches, so a
 * backlog after an outage does not hold one long lock.
 */
@Component
public class PlatformRetentionJob implements ScheduledJob {
  private static final Logger log = LoggerFactory.getLogger(PlatformRetentionJob.class);
  static final int BATCH = 5_000;

  private final Database database;
  private final RetentionProperties retention;
  private final Metrics metrics;

  public PlatformRetentionJob(Database database, RetentionProperties retention, Metrics metrics) {
    this.database = database;
    this.retention = retention;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "platform.retention";
  }

  @Override
  public String cron() {
    return "0 30 3 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.INTEGRATION;
  }

  @Override
  public void run(Instant now) {
    Timestamp receipts = before(now, retention.receiptDays());
    Timestamp outbox = before(now, retention.outboxDays());
    Timestamp consumed = before(now, retention.consumedDays());
    Timestamp runs = before(now, retention.jobRunDays());

    purge(
        "command_receipts",
        "DELETE FROM integration.command_receipts WHERE ctid IN"
            + " (SELECT ctid FROM integration.command_receipts WHERE created_at < ? LIMIT "
            + BATCH
            + ")",
        receipts);
    // History first: it is keyed by event id, and would be orphaned the other way round.
    purge(
        "outbox_attempts",
        "DELETE FROM integration.outbox_attempts WHERE event_id IN"
            + " (SELECT event_id FROM integration.outbox_events"
            + "   WHERE status = 'published' AND published_at < ? LIMIT "
            + BATCH
            + ")",
        outbox);
    purge(
        "outbox_events",
        "DELETE FROM integration.outbox_events WHERE event_id IN"
            + " (SELECT event_id FROM integration.outbox_events"
            + "   WHERE status = 'published' AND published_at < ? LIMIT "
            + BATCH
            + ")",
        outbox);
    purge(
        "consumed_events",
        "DELETE FROM integration.consumed_events WHERE ctid IN"
            + " (SELECT ctid FROM integration.consumed_events WHERE consumed_at < ? LIMIT "
            + BATCH
            + ")",
        consumed);
    purge(
        "job_runs",
        "DELETE FROM integration.job_runs WHERE run_id IN"
            + " (SELECT run_id FROM integration.job_runs WHERE started_at < ? LIMIT "
            + BATCH
            + ")",
        runs);
  }

  private void purge(String table, String sql, Timestamp cutoff) {
    int removed = database.asSystem(ModuleRole.INTEGRATION, () -> database.update(sql, cutoff));
    if (removed > 0) {
      metrics.count("waypoint.retention.purged", removed, "table", table);
      log.info("Retention removed {} row(s) from integration.{}", removed, table);
    }
  }

  private static Timestamp before(Instant now, int days) {
    return Timestamp.from(now.minus(Duration.ofDays(days)));
  }
}
