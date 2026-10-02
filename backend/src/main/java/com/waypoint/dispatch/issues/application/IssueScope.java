package com.waypoint.dispatch.issues.application;

import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The scope half of "policy AND scope" for issues (R-ISS-07). An actor may act
 * on an issue of a depot when they are the system, are scoped to the depot or to
 * the outlet the issue names, or drive a vehicle of that depot today. The last is
 * how a driver works: their scope is a vehicle on a date (R-IAM-13), not a depot,
 * so yesterday's driver cannot raise against today's depot.
 *
 * <p>Depot and outlet scope are checked in SQL inside the command's transaction
 * (rule 7); the driver's vehicle comes from Identity's contract and its depot from
 * Reference data, so this module reads neither module's tables. A refusal is
 * {@code 403}, which the bus audits.
 */
@Component
class IssueScope {
  private final Database database;
  private final IdentityQuery identity;
  private final ReferenceQuery reference;
  private final Clock clock;

  IssueScope(Database database, IdentityQuery identity, ReferenceQuery reference, Clock clock) {
    this.database = database;
    this.identity = identity;
    this.reference = reference;
    this.clock = clock;
  }

  void require(Actor actor, String depotCode, Optional<String> outletId) {
    if (inDepotOrOutlet(depotCode, outletId) || drivesForDepot(actor.userId(), depotCode)) {
      return;
    }
    throw new DomainException(
        ErrorCode.FORBIDDEN,
        "depot " + depotCode + outletId.map(o -> " / outlet " + o).orElse("") + " is outside the actor's scope",
        List.of("R-ISS-07"));
  }

  /**
   * An issue is assigned only to someone who works its depot, so it never lands
   * with a person who cannot see it (R-ISS-08).
   */
  void requireAssignable(UUID assignee, String depotCode) {
    if (!identity.scopeOf(assignee).depotCodes().contains(depotCode)) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "the assignee is not scoped to depot " + depotCode + " and could not see the issue",
          List.of("R-ISS-08"));
    }
  }

  private boolean inDepotOrOutlet(String depotCode, Optional<String> outletId) {
    Map<String, Object> scope =
        database.queryOne(
            "SELECT app.actor_is_system() OR app.actor_has_depot(?)"
                + " OR (CAST(? AS text) IS NOT NULL AND app.actor_has_outlet(?)) AS ok",
            depotCode,
            outletId.orElse(null),
            outletId.orElse(null));
    return Boolean.TRUE.equals(scope.get("ok"));
  }

  private boolean drivesForDepot(UUID actorId, String depotCode) {
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    return identity.driverVehicleOn(actorId, today)
        .flatMap(vehicle -> reference.vehicle(vehicle, null))
        .map(VehicleView::depotCode)
        .filter(depotCode::equals)
        .isPresent();
  }
}
