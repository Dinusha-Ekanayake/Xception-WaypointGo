package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.infrastructure.JdbcPositions;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * R-EXE-21: thins trails older than the service day plus 30 days to one point
 * per stop event, once a night, through a definer function only the execution
 * role may call: the role itself holds no DELETE. Batches keep each run short;
 * running twice changes nothing.
 */
@org.springframework.stereotype.Component
public class PositionRetentionJob implements ScheduledJob {
  static final int BATCH = 5000;
  static final int RETAIN_DAYS = 30;

  private final Database database;
  private final JdbcPositions positions;
  private final Metrics metrics;

  public PositionRetentionJob(Database database, JdbcPositions positions, Metrics metrics) {
    this.database = database;
    this.positions = positions;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "execution.position-retention";
  }

  /** 02:45, after proof retention and with no driver on the road. */
  @Override
  public String cron() {
    return "0 45 2 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  /** @return how many points were removed */
  int runAt(Instant now) {
    LocalDate before = now.atZone(Clock.OPERATING_ZONE).toLocalDate().minusDays(RETAIN_DAYS);
    int removed = database.asSystem(ModuleRole.EXECUTION, () -> positions.thin(before, BATCH));
    metrics.count("waypoint.execution.positions_thinned", removed);
    return removed;
  }
}
