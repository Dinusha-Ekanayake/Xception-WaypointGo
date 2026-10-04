package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.infrastructure.JdbcThreadRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Clears voice notes past their retention (P-33, MSG-11), once a night, as
 * issue photos and proof of delivery are cleared.
 *
 * <p>The audio goes; the message, the note's length and its SHA-256 stay, so it
 * is still on record that it was said. Nothing is deleted. Each note is its own
 * step, so one failure holds back none of the rest; running twice changes nothing.
 */
@Component
public class VoiceRetentionJob implements ScheduledJob {
  /** At most this many a night, so one run never holds the database for long. */
  static final int BATCH = 500;

  private final Database database;
  private final JdbcThreadRepository threads;
  private final Metrics metrics;
  private final AtomicLong heldBytes = new AtomicLong();

  public VoiceRetentionJob(Database database, JdbcThreadRepository threads, Metrics metrics) {
    this.database = database;
    this.threads = threads;
    this.metrics = metrics;
    metrics.gauge("waypoint.message.voice_bytes_held", heldBytes::get);
  }

  @Override
  public String name() {
    return "messaging.voice-retention";
  }

  /** 02:50, after proof and issue photo retention. */
  @Override
  public String cron() {
    return "0 50 2 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.MESSAGING;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many voice notes were cleared */
  public int runAt(Instant now) {
    List<UUID> due = database.asSystem(ModuleRole.MESSAGING, () -> threads.voiceDue(now, BATCH));
    int purged = 0;
    for (UUID id : due) {
      try {
        if (database.asSystem(ModuleRole.MESSAGING, () -> threads.purgeVoice(id, now))) purged++;
      } catch (DataAccessException failure) {
        metrics.increment("waypoint.message.voice_purge_failed");
      }
    }
    metrics.count("waypoint.message.voice_purged", purged);
    heldBytes.set(database.asSystem(ModuleRole.MESSAGING, threads::voiceBytesHeld));
    return purged;
  }
}
