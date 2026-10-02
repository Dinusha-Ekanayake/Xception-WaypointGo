package com.waypoint.dispatch.referencedata.application;

import com.waypoint.dispatch.identity.contract.IdentityQuery;
import com.waypoint.dispatch.identity.contract.IdentityQuery.ScopeView;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The scope half of "policy AND scope" for reference reads (R-IAM-28).
 *
 * <p>{@code reference:Read} says a role may read outlets and fleet at all. This
 * says whose: a depot's lists are for accounts scoped to that depot, one outlet
 * for the account scoped to it or to its depot, one vehicle for its depot or for
 * whoever drives it today. A driver's scope is a vehicle on a date (R-IAM-13),
 * not a depot, so a driver also reads the outlets of the depot their vehicle
 * works from, which is what a run sheet needs.
 *
 * <p>Reference data is one in-memory snapshot shared by every actor, so there is
 * no row for row-level security to hide. The check is made here instead, before
 * anything is returned, and it asks Identity through its contract: the reference
 * role cannot read the scope tables and is not given them. The version and the
 * calendar are the same for every depot and are not scoped.
 *
 * <p>Only the web reads come through here. {@link ReferenceQuery} itself stays
 * unscoped: its other callers are modules inside their own command, which have
 * already decided what the actor may reach.
 *
 * <p>A refusal is {@code 403} with an audit row, never an empty list.
 */
@Component
public class ReferenceScope {
  private static final String RULE = "R-IAM-28";

  private final IdentityQuery identity;
  private final ReferenceQuery reference;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;

  public ReferenceScope(
      IdentityQuery identity,
      ReferenceQuery reference,
      AuditLog audit,
      Metrics metrics,
      Clock clock) {
    this.identity = identity;
    this.reference = reference;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** A depot's outlets or fleet as a list: for accounts scoped to the depot. */
  public void requireDepot(Actor actor, String action, String resource, String depotCode) {
    if (actor.isSystem() || identity.scopeOf(actor.userId()).depotCodes().contains(depotCode)) {
      return;
    }
    refuse(actor, action, resource, "depot " + depotCode + " is outside the actor's scope");
  }

  /** One outlet: its own scope, its depot's, or a driver working from that depot today. */
  public void requireOutlet(Actor actor, String action, String resource, OutletView outlet) {
    if (actor.isSystem()) {
      return;
    }
    ScopeView scope = identity.scopeOf(actor.userId());
    if (scope.outletIds().contains(outlet.outletId())
        || scope.depotCodes().contains(outlet.depotCode())
        || drivesFromDepot(actor, outlet.depotCode())) {
      return;
    }
    refuse(actor, action, resource, "outlet " + outlet.outletId() + " is outside the actor's scope");
  }

  /** One vehicle: its depot's scope, or the driver assigned to it today. */
  public void requireVehicle(Actor actor, String action, String resource, VehicleView vehicle) {
    if (actor.isSystem()
        || identity.scopeOf(actor.userId()).depotCodes().contains(vehicle.depotCode())
        || drivenToday(actor).filter(vehicle.vehicleId()::equals).isPresent()) {
      return;
    }
    refuse(
        actor, action, resource, "vehicle " + vehicle.vehicleId() + " is outside the actor's scope");
  }

  private boolean drivesFromDepot(Actor actor, String depotCode) {
    return depotCode != null
        && drivenToday(actor)
            .flatMap(vehicleId -> reference.vehicle(vehicleId, null))
            .map(VehicleView::depotCode)
            .filter(depotCode::equals)
            .isPresent();
  }

  /** "Today" is the operating day by the server's clock, as it is for a delivery (EXE-13). */
  private Optional<String> drivenToday(Actor actor) {
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    return identity.driverVehicleOn(actor.userId(), today);
  }

  private void refuse(Actor actor, String action, String resource, String reason) {
    metrics.increment("waypoint.scope.denied", "module", "referencedata");
    // Policy allowed this read, so nothing else has recorded the attempt.
    audit.recordStandalone(
        AuditEntry.denied(actor.userId(), actor.deviceId(), action, resource, reason + " (" + RULE + ")"));
    throw new DomainException(ErrorCode.FORBIDDEN, reason, List.of(RULE));
  }
}
