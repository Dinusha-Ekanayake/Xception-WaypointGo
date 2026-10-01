package com.waypoint.dispatch.platform.scheduling;

import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link ScheduledJob} on its cron, in {@code Asia/Colombo}.
 *
 * <p>Before each run it takes a session advisory lock named after the job on a
 * connection of its own, and skips the run if another replica holds it (PLT-04).
 * The lock lives as long as that connection, so a crashed replica releases it.
 * A failing run is logged and counted, never rethrown: one bad run must not
 * unschedule the job.
 *
 * <p>Off with {@code SCHEDULING_ENABLED=false}, which tests use so a job cannot
 * fire in the middle of a test.
 *
 * <p>Minimal until the platform scheduler of issue #6 replaces it.
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobRunner implements SmartInitializingSingleton, DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(ScheduledJobRunner.class);

  private final List<ScheduledJob> jobs;
  private final DataSource dataSource;
  private final Clock clock;
  private final Metrics metrics;
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

  public ScheduledJobRunner(
      List<ScheduledJob> jobs, DataSource dataSource, Clock clock, Metrics metrics) {
    this.jobs = jobs;
    this.dataSource = dataSource;
    this.clock = clock;
    this.metrics = metrics;
  }

  @Override
  public void afterSingletonsInstantiated() {
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("waypoint-job-");
    scheduler.initialize();
    TimeZone zone = TimeZone.getTimeZone(Clock.OPERATING_ZONE);
    for (ScheduledJob job : jobs) {
      scheduler.schedule(() -> runOnce(job), new CronTrigger(job.cron(), zone));
      log.info("Scheduled job {} on '{}'", job.name(), job.cron());
    }
  }

  /** One run under the job's lease. Public so an operator path or a test can trigger it. */
  public boolean runOnce(ScheduledJob job) {
    try (Connection lease = dataSource.getConnection()) {
      if (!lock(lease, "pg_try_advisory_lock", job.name())) {
        metrics.increment("waypoint.job.skipped", "job", job.name(), "reason", "lease_held");
        return false;
      }
      try {
        metrics.time("waypoint.job.duration", () -> {
          job.run(clock.now());
          return null;
        }, "job", job.name());
        metrics.increment("waypoint.job.run", "job", job.name(), "outcome", "ok");
        return true;
      } finally {
        lock(lease, "pg_advisory_unlock", job.name());
      }
    } catch (RuntimeException | SQLException e) {
      metrics.increment("waypoint.job.run", "job", job.name(), "outcome", "failed");
      log.warn("Job {} failed: {}", job.name(), e.toString());
      return false;
    }
  }

  private static boolean lock(Connection connection, String function, String name)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT " + function + "(hashtext(?))")) {
      statement.setString(1, "job:" + name);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() && rows.getBoolean(1);
      }
    }
  }

  @Override
  public void destroy() {
    scheduler.shutdown();
  }
}
