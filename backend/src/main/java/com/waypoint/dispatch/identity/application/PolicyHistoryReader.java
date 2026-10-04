package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.infrastructure.JdbcPolicyRepository;
import com.waypoint.dispatch.platform.audit.PolicyHistory;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Answers "which policy versions governed this actor then" for the audit read
 * (POL-03). A policy version is immutable, so a version that existed at a moment
 * says exactly what rules it carried; what changes is which version was the
 * default and which policies were attached, and attachments leave no history.
 *
 * <p>So the answer is exact when the policy generation is unchanged since the
 * decision, which the caller can see by comparing the generation stamped on the
 * audit row with {@link PolicyHistory.Snapshot#currentGeneration()}, and a best
 * effort otherwise. It says which it is rather than pretending.
 */
@Component
public class PolicyHistoryReader implements PolicyHistory {
  private final Database database;
  private final JdbcPolicyRepository policies;

  public PolicyHistoryReader(Database database, JdbcPolicyRepository policies) {
    this.database = database;
    this.policies = policies;
  }

  @Override
  public Snapshot at(UUID actorId, Instant moment) {
    return database.readAs(
        ModuleRole.IAM,
        actorId,
        () -> {
          long generation = policies.generation();
          List<VersionInForce> versions =
              database
                  .query(
                      """
                      SELECT p.name, v.version_number
                        FROM iam.policies p
                        JOIN LATERAL (
                              SELECT version_number
                                FROM iam.policy_versions
                               WHERE policy_id = p.policy_id AND created_at <= ?
                               ORDER BY version_number DESC
                               LIMIT 1) v ON true
                       WHERE p.policy_id IN (
                              SELECT a.policy_id
                                FROM iam.policy_attachments a
                               WHERE (a.principal_type = 'user' AND a.principal_id = ?::text)
                                  OR (a.principal_type = 'role' AND a.principal_id IN
                                      (SELECT role_code FROM iam.user_roles WHERE user_id = ?)))
                       ORDER BY p.name
                      """,
                      Timestamp.from(moment),
                      actorId.toString(),
                      actorId)
                  .stream()
                  .map(
                      row ->
                          new VersionInForce(
                              (String) row.get("name"),
                              ((Number) row.get("version_number")).intValue()))
                  .toList();
          return new Snapshot(generation, versions);
        });
  }
}
