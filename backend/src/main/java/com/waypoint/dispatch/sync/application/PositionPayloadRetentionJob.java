package com.waypoint.dispatch.sync.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.sync.infrastructure.OperationRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * A queued {@code delivery:RecordPositions} is kept here as sent, GPS points
 * included. Past the trail retention window (R-EXE-21) the points are removed
 * from that copy and the envelope stays, so the operation is still on record.
 */
@Component
public class PositionPayloadRetentionJob implements ScheduledJob {
  static final int BATCH = 5000;
  /** The trail window (service day plus 30 days) with a day's margin for late service days. */
  static final Duration AGE = Duration.ofDays(32);

  private final Database database;
  private final OperationRepository operations;
  private final Metrics metrics;

  public PositionPayloadRetentionJob(Database database, OperationRepository operations, Metrics metrics) {
    this.database = database;
    this.operations = operations;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "sync.position-payload-retention";
  }

  @Override
  public String cron() {
    return "0 50 2 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.SYNC;
  }

  @Override
  public void run(Instant now) {
    runAt(now);
  }

  public int runAt(Instant now) {
    int redacted =
        database.asSystem(ModuleRole.SYNC, () -> operations.redactPositionPayloads(now.minus(AGE), BATCH));
    metrics.count("waypoint.sync.position_payloads_redacted", redacted);
    return redacted;
  }
}
