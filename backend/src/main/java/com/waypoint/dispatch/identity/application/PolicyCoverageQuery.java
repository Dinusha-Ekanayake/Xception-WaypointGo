package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * SEC-10: active accounts that no policy reaches, directly or through a role.
 *
 * <p>Such an account is denied everything, which is safe, but it is also a person
 * who cannot work and will be told "permission denied" with no clue why. Exposed
 * as a gauge so a non-zero value is visible before the support call.
 *
 * <p>Computed on scrape, at most once a minute, so a scrape interval shorter than
 * that does not turn into a query per scrape.
 */
@Component
public class PolicyCoverageQuery {
  private static final Duration REFRESH = Duration.ofMinutes(1);

  private final Database database;
  private final Clock clock;
  private volatile long cached = -1;
  private volatile Instant computedAt = Instant.EPOCH;

  public PolicyCoverageQuery(Database database, Clock clock, Metrics metrics) {
    this.database = database;
    this.clock = clock.realTime();
    metrics.gauge("waypoint.iam.actors_without_policy", this::actorsWithoutPolicy);
  }

  /** @return the count, or -1 when the database could not answer */
  public long actorsWithoutPolicy() {
    Instant now = clock.now();
    if (cached >= 0 && computedAt.plus(REFRESH).isAfter(now)) {
      return cached;
    }
    try {
      Map<String, Object> row =
          database.asModule(
              ModuleRole.IAM,
              null,
              () ->
                  database.queryOne(
                      """
                      SELECT count(*) AS uncovered
                      FROM iam.users u
                      WHERE u.is_active
                        AND NOT EXISTS (
                          SELECT 1 FROM iam.policy_attachments a
                          WHERE (a.principal_type = 'user' AND a.principal_id = u.user_id::text)
                             OR (a.principal_type = 'role' AND a.principal_id IN (
                                   SELECT r.role_code FROM iam.user_roles r
                                   WHERE r.user_id = u.user_id)))
                      """));
      cached = row == null ? 0 : ((Number) row.get("uncovered")).longValue();
      computedAt = now;
      return cached;
    } catch (RuntimeException e) {
      // A scrape must never fail because the database is down; readiness says that.
      return -1;
    }
  }
}
