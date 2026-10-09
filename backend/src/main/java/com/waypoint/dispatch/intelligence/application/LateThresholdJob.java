package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.domain.LateThreshold;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcLateNotices;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Component;

/**
 * Roadmap MF-04, T1-02, R-ML-22: freezes the threshold of each model's late
 * flag once its last eight finished weeks hold enough labelled stops. Nightly,
 * after the day's outcomes are kept; a model frozen once is never looked at
 * again.
 */
@Component
public class LateThresholdJob implements ScheduledJob {
  static final int WEEKS = 8;

  private final Database database;
  private final JdbcLateNotices notices;
  private final Metrics metrics;

  public LateThresholdJob(Database database, JdbcLateNotices notices, Metrics metrics) {
    this.database = database;
    this.notices = notices;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "ml.late-threshold";
  }

  @Override
  public String cron() {
    return "0 30 3 * * *";
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

  /** @return how many models had their threshold frozen */
  int runAt(Instant now) {
    LocalDate thisMonday = now.atZone(Clock.OPERATING_ZONE).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate from = thisMonday.minusWeeks(WEEKS);
    LocalDate to = thisMonday.minusDays(1);
    int frozen = 0;
    for (String label : database.asSystem(ModuleRole.ML, () -> notices.unfrozenLabels(from, to))) {
      var choice = LateThreshold.choose(database.asSystem(ModuleRole.ML, () -> notices.scored(label, from, to)));
      if (choice.isPresent()) {
        database.asSystem(ModuleRole.ML, () -> notices.freeze(label, choice.get(), from, to, now));
        metrics.increment("waypoint.ml.late_threshold_frozen");
        frozen++;
      }
    }
    return frozen;
  }
}
