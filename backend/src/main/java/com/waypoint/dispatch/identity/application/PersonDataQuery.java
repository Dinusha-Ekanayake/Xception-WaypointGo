package com.waypoint.dispatch.identity.application;

import com.waypoint.dispatch.identity.contract.PersonQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link PersonQuery} over iam.users. Runs as a separate read under the identity
 * role, so a caller inside another module's transaction never switches role.
 */
@Component
public class PersonDataQuery implements PersonQuery {
  private final Database database;

  public PersonDataQuery(Database database) {
    this.database = database;
  }

  @Override
  public Optional<PersonView> person(UUID userId) {
    if (userId == null) {
      return Optional.empty();
    }
    Map<String, Object> row =
        database.readAs(
            ModuleRole.IAM,
            null,
            () ->
                database.queryOne(
                    "SELECT user_id, display_name, employee_code FROM iam.users WHERE user_id = ?",
                    userId));
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new PersonView(
            (UUID) row.get("user_id"),
            (String) row.get("display_name"),
            Optional.ofNullable((String) row.get("employee_code"))));
  }
}
