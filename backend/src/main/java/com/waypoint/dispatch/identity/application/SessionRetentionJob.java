package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.config.RetentionProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Clears what sign-in leaves behind (issue #6): sessions that expired, and
 * sign-in attempts older than any throttle window could look at.
 *
 * <p>An expired session already authenticates nobody, because every read checks
 * both expiries; this only stops the table growing for ever. The operator history
 * ({@code iam.session_operators}) is not touched: who operated a shared device is
 * evidence for a queued write replayed later, and is kept.
 *
 * <p>Lives in Identity because Identity owns these tables. Platform's scheduler
 * runs it through the same port as every other module's job.
 */
@Component
public class SessionRetentionJob implements ScheduledJob {
  private static final Logger log = LoggerFactory.getLogger(SessionRetentionJob.class);
  private static final int BATCH = 5_000;

  private final Database database;
  private final RetentionProperties retention;
  private final Metrics metrics;

  public SessionRetentionJob(Database database, RetentionProperties retention, Metrics metrics) {
    this.database = database;
    this.retention = retention;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "iam.retention";
  }

  @Override
  public String cron() {
    return "0 45 3 * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.IAM;
  }

  @Override
  public void run(Instant now) {
    Timestamp sessionCutoff = Timestamp.from(now.minus(Duration.ofDays(retention.sessionGraceDays())));
    Timestamp attemptCutoff = Timestamp.from(now.minus(Duration.ofDays(retention.loginAttemptDays())));

    int sessions =
        database.asSystem(
            ModuleRole.IAM,
            () ->
                database.update(
                    "DELETE FROM iam.sessions WHERE token_hash IN"
                        + " (SELECT token_hash FROM iam.sessions"
                        + "   WHERE absolute_expiry < ? OR idle_expiry < ? LIMIT "
                        + BATCH
                        + ")",
                    sessionCutoff,
                    sessionCutoff));
    int attempts =
        database.asSystem(
            ModuleRole.IAM,
            () ->
                database.update(
                    "DELETE FROM iam.login_attempts WHERE ctid IN"
                        + " (SELECT ctid FROM iam.login_attempts WHERE attempted_at < ? LIMIT "
                        + BATCH
                        + ")",
                    attemptCutoff));
    // Remote MCP bookkeeping (R-IAM-31): codes long past use, then registrations
    // nobody ever signed in through. A client someone did use is kept.
    int codes =
        database.asSystem(
            ModuleRole.IAM,
            () ->
                database.update(
                    "DELETE FROM iam.oauth_authorization_codes WHERE expires_at < ?", sessionCutoff));
    int clients =
        database.asSystem(
            ModuleRole.IAM,
            () ->
                database.update(
                    "DELETE FROM iam.oauth_clients c WHERE c.last_used_at IS NULL AND c.registered_at < ?"
                        + " AND NOT EXISTS (SELECT 1 FROM iam.oauth_authorization_codes a"
                        + " WHERE a.client_id = c.client_id)",
                    sessionCutoff));
    if (codes > 0) {
      metrics.count("waypoint.retention.purged", codes, "table", "iam.oauth_authorization_codes");
    }
    if (clients > 0) {
      metrics.count("waypoint.retention.purged", clients, "table", "iam.oauth_clients");
    }
    if (sessions > 0) {
      metrics.count("waypoint.retention.purged", sessions, "table", "iam.sessions");
    }
    if (attempts > 0) {
      metrics.count("waypoint.retention.purged", attempts, "table", "iam.login_attempts");
    }
    if (sessions + attempts > 0) {
      log.info("Retention removed {} expired session(s) and {} sign-in attempt(s)", sessions, attempts);
    }
  }
}
