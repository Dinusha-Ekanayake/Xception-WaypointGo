package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.intelligence.contract.AiHealthViews.AiHealthView;
import com.waypoint.dispatch.intelligence.infrastructure.JdbcAiHealth;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Where AI fails (R-ML-27): each use's outcomes and response times by day, the
 * newest failures with their masked input, Jev's agreement per prompt version,
 * and how often suggested decisions are taken. The caller is authorized for
 * {@code ml:Read} on the model registry first; the tables are the system's.
 */
@Component
public class AiHealthQuery {
  static final int MAX_DAYS = 90;

  private final Database database;
  private final JdbcAiHealth health;
  private final Clock clock;

  public AiHealthQuery(Database database, JdbcAiHealth health, Clock clock) {
    this.database = database;
    this.health = health;
    this.clock = clock;
  }

  public AiHealthView over(int days) {
    int span = Math.max(1, Math.min(MAX_DAYS, days));
    Instant since = clock.now().minus(Duration.ofDays(span));
    return database.asSystem(ModuleRole.ML, () -> new AiHealthView(
        span, health.callDays(since), health.failures(since), health.jev(since), health.suggestions(since)));
  }
}
