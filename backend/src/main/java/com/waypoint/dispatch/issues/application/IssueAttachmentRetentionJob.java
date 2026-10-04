package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.issues.infrastructure.JdbcIssueAttachments;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueAttachments.Expired;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Clears issue photos past their retention (P-14), once a night, as proof of
 * delivery is cleared.
 *
 * <p>The bytes go; the row, its size and its SHA-256 stay, so it is still on
 * record that the photo existed. Nothing is deleted. Each photo is its own step,
 * so one failure holds back none of the rest; running twice changes nothing.
 */
@Component
public class IssueAttachmentRetentionJob implements ScheduledJob {
  /** At most this many a night, so one run never holds the database for long. */
  static final int BATCH = 500;

  private final Database database;
  private final JdbcIssueAttachments attachments;
  private final Metrics metrics;
  private final AtomicLong heldBytes = new AtomicLong();

  public IssueAttachmentRetentionJob(Database database, JdbcIssueAttachments attachments, Metrics metrics) {
    this.database = database;
    this.attachments = attachments;
    this.metrics = metrics;
    metrics.gauge("waypoint.issues.attachment_bytes_held", heldBytes::get);
  }

  @Override
  public String name() {
    return "issues.attachment-retention";
  }

  /** 02:40, after proof retention, when no store is counting. */
  @Override
  public String cron() {
    return "0 40 2 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ISSUES;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many photos were cleared */
  int runAt(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    List<Expired> due = database.asSystem(ModuleRole.ISSUES, () -> attachments.expired(today, BATCH));
    int purged = 0;
    for (Expired e : due) {
      try {
        database.asSystem(ModuleRole.ISSUES, () -> attachments.purge(e, now));
        purged++;
      } catch (DataAccessException failure) {
        metrics.increment("waypoint.issues.attachment_purge_failed");
      }
    }
    metrics.count("waypoint.issues.attachment_purged", purged);
    heldBytes.set(database.asSystem(ModuleRole.ISSUES, attachments::heldBytes));
    return purged;
  }
}
