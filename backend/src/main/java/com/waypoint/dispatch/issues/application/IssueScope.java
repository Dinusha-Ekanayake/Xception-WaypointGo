package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The scope half of "policy AND scope" for a decision on an issue: the system,
 * someone scoped to the issue's depot, or someone scoped to its outlet. Checked
 * in SQL inside the command's transaction (rule 7); a refusal is {@code 403},
 * which the bus audits.
 */
final class IssueScope {
  private IssueScope() {}

  static void require(Database database, String depotCode, Optional<String> outletId) {
    Map<String, Object> scope =
        database.queryOne(
            "SELECT app.actor_is_system() OR app.actor_has_depot(?)"
                + " OR (CAST(? AS text) IS NOT NULL AND app.actor_has_outlet(?)) AS ok",
            depotCode,
            outletId.orElse(null),
            outletId.orElse(null));
    if (!Boolean.TRUE.equals(scope.get("ok"))) {
      throw new DomainException(
          ErrorCode.FORBIDDEN,
          "depot " + depotCode + outletId.map(o -> " / outlet " + o).orElse("") + " is outside the actor's scope",
          List.of("R-ISS-07"));
    }
  }
}
