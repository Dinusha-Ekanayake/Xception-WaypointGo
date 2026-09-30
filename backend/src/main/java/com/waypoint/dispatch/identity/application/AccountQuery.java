package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Reading accounts, their scope and their vehicle assignments.
 *
 * <p>Separate from {@link AccountAdminUseCase} because reads and writes have
 * different shapes and different risks, and because every write now arrives as a
 * command. A controller for accounts is therefore a read surface: the only thing
 * it needs to offer besides the data is {@code rowVersion}, which is what a client
 * sends back as {@code expectedVersion}. Without it no caller could satisfy the
 * version guard.
 *
 * <p>Password hashes are never selected. Not redacted, never read.
 */
@Component
public class AccountQuery {
  private static final int MAX_PAGE = 200;

  private final Database database;

  public AccountQuery(Database database) {
    this.database = database;
  }

  public record AccountView(
      UUID userId,
      String email,
      String displayName,
      boolean active,
      long rowVersion,
      List<String> roles,
      List<String> depots,
      List<String> outlets) {}

  public record AssignmentView(
      UUID assignmentId,
      String vehicleId,
      UUID driverUserId,
      String driverName,
      LocalDate from,
      LocalDate until) {}

  /** Keyset paginated on email, which is unique and stable. Never OFFSET. */
  public List<AccountView> page(String afterEmail, int limit) {
    int size = Math.min(limit <= 0 ? 50 : limit, MAX_PAGE);
    return select("(?::text IS NULL OR u.email > ?::text)", size, afterEmail, afterEmail);
  }

  public AccountView byId(UUID userId) {
    return select("u.user_id = ?", 1, userId).stream()
        .findFirst()
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No account " + userId));
  }

  /**
   * One shape for every account read, so a column added here cannot be missing
   * from the other endpoint. The roles and scopes are aggregated rather than
   * fetched per row: 120 outlets across a handful of managers is one query.
   */
  private List<AccountView> select(String where, int limit, Object... params) {
    Object[] arguments = new Object[params.length + 1];
    System.arraycopy(params, 0, arguments, 0, params.length);
    arguments[params.length] = limit;

    return database.asModule(
        ModuleRole.IAM,
        null,
        () ->
            database
                .query(
                    """
                    SELECT u.user_id, u.email, u.display_name, u.is_active, u.row_version,
                           coalesce(r.roles, '{}')   AS roles,
                           coalesce(d.depots, '{}')  AS depots,
                           coalesce(o.outlets, '{}') AS outlets
                    FROM iam.users u
                    LEFT JOIN (SELECT user_id, array_agg(role_code ORDER BY role_code) AS roles
                                 FROM iam.user_roles GROUP BY user_id) r ON r.user_id = u.user_id
                    LEFT JOIN (SELECT user_id, array_agg(depot_code ORDER BY depot_code) AS depots
                                 FROM iam.user_depot_access GROUP BY user_id) d ON d.user_id = u.user_id
                    LEFT JOIN (SELECT user_id, array_agg(outlet_id ORDER BY outlet_id) AS outlets
                                 FROM iam.user_outlet_access GROUP BY user_id) o ON o.user_id = u.user_id
                    WHERE %s
                    ORDER BY u.email
                    LIMIT ?
                    """
                        .formatted(where),
                    arguments)
                .stream()
                .map(AccountQuery::toAccount)
                .toList());
  }

  /** Assignments touching a date, or all current and future ones when none is given. */
  public List<AssignmentView> assignments(LocalDate on) {
    return database.asModule(
        ModuleRole.IAM,
        null,
        () ->
            database
                .query(
                    """
                    SELECT a.assignment_id, a.vehicle_id, a.driver_user_id, u.display_name,
                           lower(a.validity) AS starts_on, upper(a.validity) AS ends_on
                    FROM iam.vehicle_driver_assignments a
                    JOIN iam.users u ON u.user_id = a.driver_user_id
                    WHERE (?::date IS NULL OR a.validity @> ?::date)
                    ORDER BY a.vehicle_id, lower(a.validity)
                    """,
                    on == null ? null : java.sql.Date.valueOf(on),
                    on == null ? null : java.sql.Date.valueOf(on))
                .stream()
                .map(
                    r ->
                        new AssignmentView(
                            (UUID) r.get("assignment_id"),
                            (String) r.get("vehicle_id"),
                            (UUID) r.get("driver_user_id"),
                            (String) r.get("display_name"),
                            date(r.get("starts_on")),
                            date(r.get("ends_on"))))
                .toList());
  }

  private static AccountView toAccount(Map<String, Object> r) {
    return new AccountView(
        (UUID) r.get("user_id"),
        (String) r.get("email"),
        (String) r.get("display_name"),
        (Boolean) r.get("is_active"),
        ((Number) r.get("row_version")).longValue(),
        strings(r.get("roles")),
        strings(r.get("depots")),
        strings(r.get("outlets")));
  }

  private static List<String> strings(Object array) {
    if (array == null) {
      return List.of();
    }
    try {
      Object[] values = (Object[]) ((java.sql.Array) array).getArray();
      return java.util.Arrays.stream(values).filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Could not read a text array", e);
    }
  }

  private static LocalDate date(Object value) {
    return value == null ? null : ((java.sql.Date) value).toLocalDate();
  }
}
