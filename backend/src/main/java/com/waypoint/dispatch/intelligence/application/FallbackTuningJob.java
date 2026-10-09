package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.domain.FallbackTuning;
import com.waypoint.dispatch.intelligence.domain.FallbackTuning.LateTuning;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcOutcomes;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Each night, after the outcomes and the metrics, the fallback relearns from
 * the stops it scored itself over the last eight weeks (R-ML-14). The fit is
 * kept as a new row; too few labels and nothing is written, so the fallback
 * stays the documented rule until there is evidence to tune it with.
 */
@Component
public class FallbackTuningJob implements ScheduledJob {
  static final int WEEKS = 8;

  private final Database database;
  private final JdbcOutcomes outcomes;
  private final SecureRandom random = new SecureRandom();
  private final AtomicLong labels = new AtomicLong();

  FallbackTuningJob(Database database, JdbcOutcomes outcomes, Metrics metrics) {
    this.database = database;
    this.outcomes = outcomes;
    metrics.gauge("waypoint.ml.fallback_tuning_labels", labels::get);
  }

  @Override
  public String name() {
    return "intelligence.fallback-tuning";
  }

  @Override
  public String cron() {
    return "0 40 3 * * *";
  }

  @Override
  public boolean onBusinessClock() {
    return true;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.ML;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return the fit written, or the identity when there was too little to fit */
  public LateTuning runAt(Instant now) {
    LocalDate to = now.atZone(Clock.OPERATING_ZONE).toLocalDate().minusDays(1);
    LocalDate from = to.minusWeeks(WEEKS);
    LateTuning tuning = FallbackTuning.fit(database.asSystem(ModuleRole.ML, () -> outcomes.fallbackLabelled(from, to)));
    if (tuning.labels() > 0) {
      database.asSystem(ModuleRole.ML, () -> outcomes.saveTuning(UuidV7.generate(now, random), tuning, from, to, now));
    }
    labels.set(tuning.labels());
    return tuning;
  }
}
