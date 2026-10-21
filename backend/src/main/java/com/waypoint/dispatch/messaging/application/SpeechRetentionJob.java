package com.waypoint.dispatch.messaging.application;

import com.waypoint.dispatch.messaging.infrastructure.JdbcSpeech;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * R-MSG-10: the server's spoken copies are a cache. Once a night, a render no
 * message has asked for in 30 days loses its audio (the row stays); the same
 * words in a later message are spoken again. Also measures how often one render
 * serves several messages, the number that says whether keeping them pays.
 */
@Component
public class SpeechRetentionJob implements ScheduledJob {
  static final Duration UNUSED = Duration.ofDays(30);
  /** At most this many a night, so one run never holds the database for long. */
  static final int BATCH = 2_000;

  private final Database database;
  private final JdbcSpeech speech;
  private final Metrics metrics;
  private final AtomicLong held = new AtomicLong();
  private final AtomicLong spoken = new AtomicLong();
  private final AtomicLong texts = new AtomicLong();

  public SpeechRetentionJob(Database database, JdbcSpeech speech, Metrics metrics) {
    this.database = database;
    this.speech = speech;
    this.metrics = metrics;
    metrics.gauge("waypoint.speech.renders_held", held::get);
    metrics.gauge("waypoint.speech.messages_spoken_30d", spoken::get);
    metrics.gauge("waypoint.speech.texts_spoken_30d", texts::get);
  }

  @Override
  public String name() {
    return "messaging.speech-retention";
  }

  /** 03:05, after voice note retention. */
  @Override
  public String cron() {
    return "0 5 3 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.MESSAGING;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many renders were cleared */
  public int runAt(Instant now) {
    Instant cutoff = now.minus(UNUSED);
    int cleared = database.asSystem(ModuleRole.MESSAGING, () -> speech.clearUnused(cutoff, now, BATCH));
    metrics.count("waypoint.speech.renders_cleared", cleared);
    Map<String, Object> reuse = database.asSystem(ModuleRole.MESSAGING, () -> speech.reuse(cutoff));
    held.set(((Number) reuse.get("held")).longValue());
    spoken.set(((Number) reuse.get("spoken")).longValue());
    texts.set(((Number) reuse.get("texts")).longValue());
    return cleared;
  }
}
