package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What other modules may ask Identity, answered.
 *
 * <p>Every read runs as {@code waypoint_iam} in a read-only transaction of its
 * own ({@link Database#readAs}), because the caller is usually inside another
 * module's command and that module's role cannot read these tables (D-B).
 *
 * <p>These answer about whichever account the caller names, not about the actor
 * of the unit of work: Notification asks who should hear about a depot, Planning
 * asks which vehicle someone else drives. The identity role sees every row of
 * the scope tables for that reason, and nothing here returns a credential.
 */
@Component
public class IdentityDataQuery implements IdentityQuery {
  private final Database database;
  private final PolicyDecisionPoint decisions;

  public IdentityDataQuery(Database database, PolicyDecisionPoint decisions) {
    this.database = database;
    this.decisions = decisions;
  }

  /** The policy half of {@code policy AND scope}. Answers without auditing: it is a question. */
  @Override
  public boolean permits(UUID userId, String action, String resource) {
    return decisions.decide(Actor.user(userId), action, resource, Map.of()).allowed();
  }

  @Override
  public ScopeView scopeOf(UUID userId) {
    return read(
        () ->
            new ScopeView(
                userId,
                column("SELECT role_code AS v FROM iam.user_roles WHERE user_id = ? ORDER BY 1", userId),
                column(
                    "SELECT depot_code AS v FROM iam.user_depot_access WHERE user_id = ? ORDER BY 1",
                    userId),
                column(
                    "SELECT outlet_id AS v FROM iam.user_outlet_access WHERE user_id = ? ORDER BY 1",
                    userId)));
  }

  /**
   * R-IAM-13. The range is half open, so an assignment that ended on a date does
   * not cover it, and the exclusion constraint guarantees at most one row.
   */
  @Override
  public Optional<String> driverVehicleOn(UUID userId, LocalDate date) {
    return read(
        () ->
            column(
                    "SELECT vehicle_id AS v FROM iam.vehicle_driver_assignments"
                        + " WHERE driver_user_id = ? AND validity @> ?::date ORDER BY 1",
                    userId,
                    Date.valueOf(date))
                .stream()
                .findFirst());
  }

  /**
   * Active accounts only: a disabled account has no session to read a
   * notification with. A vehicle's recipients are whoever is assigned to it
   * today, in the operating zone's date.
   */
  @Override
  public List<UUID> recipientsFor(String roleCode, String scopeType, String scopeId) {
    String scoped =
        switch (scopeType) {
          case "depot" ->
              "EXISTS (SELECT 1 FROM iam.user_depot_access s"
                  + " WHERE s.user_id = u.user_id AND s.depot_code = ?)";
          case "outlet" ->
              "EXISTS (SELECT 1 FROM iam.user_outlet_access s"
                  + " WHERE s.user_id = u.user_id AND s.outlet_id = ?)";
          case "vehicle" ->
              "EXISTS (SELECT 1 FROM iam.vehicle_driver_assignments s"
                  + " WHERE s.driver_user_id = u.user_id AND s.vehicle_id = ?"
                  + " AND s.validity @> (now() AT TIME ZONE 'Asia/Colombo')::date)";
          default ->
              throw new IllegalArgumentException(
                  "scopeType is depot, outlet or vehicle, not " + scopeType);
        };
    return read(
        () ->
            database
                .query(
                    "SELECT u.user_id FROM iam.users u"
                        + " JOIN iam.user_roles r ON r.user_id = u.user_id AND r.role_code = ?"
                        + " WHERE u.is_active AND "
                        + scoped
                        + " ORDER BY u.user_id",
                    roleCode,
                    scopeId)
                .stream()
                .map(row -> (UUID) row.get("user_id"))
                .toList());
  }

  private List<String> column(String sql, Object... params) {
    return database.query(sql, params).stream().map(row -> (String) row.get("v")).toList();
  }

  private <T> T read(java.util.function.Supplier<T> work) {
    return database.readAs(ModuleRole.IAM, database.ambientActor().orElse(null), work);
  }
}
