package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.domain.ProofStore;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.ExpiredAttachment;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.util.Clock;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Clears proof artifacts past their retention (P-14), once a night.
 *
 * <p>The bytes go; the attachment row, its size and its SHA-256 stay, so it is
 * still on record that the proof existed and what it was. Nothing is deleted.
 * Each artifact is its own step, so one failure holds back none of the rest and
 * is retried on the next run; running twice changes nothing, because a cleared
 * artifact is no longer due.
 *
 * <p>Also reports how many bytes of evidence are held, which is what fills the
 * database's storage quota.
 */
@Component
public class ProofRetentionJob implements ScheduledJob {
  /** At most this many a night, so one run never holds the database for long. */
  static final int BATCH = 500;

  private final Database database;
  private final JdbcDeliveryRepository deliveries;
  private final ProofStore store;
  private final Metrics metrics;
  private final AtomicLong storedBytes = new AtomicLong();

  public ProofRetentionJob(
      Database database, JdbcDeliveryRepository deliveries, ProofStore store, Metrics metrics) {
    this.database = database;
    this.deliveries = deliveries;
    this.store = store;
    this.metrics = metrics;
    metrics.gauge("waypoint.execution.proof_bytes_held", storedBytes::get);
  }

  @Override
  public String name() {
    return "execution.proof-retention";
  }

  /** 02:30, when no driver is on the road. */
  @Override
  public String cron() {
    return "0 30 2 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many artifacts were cleared */
  int runAt(Instant now) {
    LocalDate today = now.atZone(Clock.OPERATING_ZONE).toLocalDate();
    List<ExpiredAttachment> due =
        database.asSystem(ModuleRole.EXECUTION, () -> deliveries.expiredAttachments(today, BATCH));
    int purged = 0;
    for (ExpiredAttachment attachment : due) {
      try {
        // The bytes first: a cleared row with bytes left behind would be evidence kept past its time.
        store.purge(attachment.storageKey());
        database.asSystem(ModuleRole.EXECUTION, () -> deliveries.markPurged(attachment.attachmentId(), now));
        purged++;
      } catch (DataAccessException | UncheckedIOException e) {
        metrics.increment("waypoint.execution.proof_purge_failed");
      }
    }
    metrics.count("waypoint.execution.proof_purged", purged);
    storedBytes.set(database.asSystem(ModuleRole.EXECUTION, deliveries::heldAttachmentBytes));
    return purged;
  }
}
