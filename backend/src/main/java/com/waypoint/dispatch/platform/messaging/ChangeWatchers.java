package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Opens a watch on the change signals for an actor's depots (roadmap F-07b),
 * after checking each depot against the actor's scope: policy alone allows the
 * action, the scope tables decide the depots. A depot outside it is refused and
 * audited, never answered with a stream that stays silent.
 */
@Component
public class ChangeWatchers {
  private final ChangeSignals signals;
  private final Database database;
  private final AuditLog audit;

  public ChangeWatchers(ChangeSignals signals, Database database, AuditLog audit) {
    this.signals = signals;
    this.database = database;
    this.audit = audit;
  }

  /** @return what stops watching */
  public Runnable open(Actor actor, String action, Set<String> depots, ChangeSignals.Listener listener) {
    for (String depot : depots) {
      Map<String, Object> row =
          database.readAs(
              ModuleRole.PLANNING,
              actor.userId(),
              () -> database.queryOne("SELECT app.actor_is_system() OR app.actor_has_depot(?) AS ok", depot));
      if (row == null || !Boolean.TRUE.equals(row.get("ok"))) {
        audit.recordStandalone(
            AuditEntry.denied(actor.userId(), actor.deviceId(), action, "wpt:plan:depot:" + depot, "outside the actor's scope"));
        throw new DomainException(ErrorCode.FORBIDDEN, "Depot " + depot + " is outside the actor's scope");
      }
    }
    return signals.listen(depots, listener);
  }

  /**
   * A store's watch on its outlets: each checked against the actor's outlet
   * scope, refused and audited when outside it, like a depot above.
   *
   * @return what stops watching
   */
  public Runnable openOutlets(Actor actor, String action, Set<String> outlets, ChangeSignals.Listener listener) {
    for (String outlet : outlets) {
      Map<String, Object> row =
          database.readAs(
              ModuleRole.RECEIPT,
              actor.userId(),
              () -> database.queryOne("SELECT app.actor_is_system() OR app.actor_has_outlet(?) AS ok", outlet));
      if (row == null || !Boolean.TRUE.equals(row.get("ok"))) {
        audit.recordStandalone(
            AuditEntry.denied(actor.userId(), actor.deviceId(), action, "wpt:receipt:outlet:" + outlet, "outside the actor's scope"));
        throw new DomainException(ErrorCode.FORBIDDEN, "Outlet " + outlet + " is outside the actor's scope");
      }
    }
    return signals.listen(Set.of(), outlets, listener);
  }

  /**
   * A driver's watch on the vehicle they drive on a day (live refresh, phase 3),
   * checked against the driver scope; refused and audited when outside it.
   *
   * @return what stops watching
   */
  public Runnable openVehicle(
      Actor actor, String action, String vehicleId, java.time.LocalDate day, ChangeSignals.Listener listener) {
    Map<String, Object> row =
        database.readAs(
            ModuleRole.EXECUTION,
            actor.userId(),
            () -> database.queryOne(
                "SELECT app.actor_is_system() OR app.actor_drives(?, ?) AS ok", vehicleId, java.sql.Date.valueOf(day)));
    if (row == null || !Boolean.TRUE.equals(row.get("ok"))) {
      audit.recordStandalone(
          AuditEntry.denied(actor.userId(), actor.deviceId(), action, "wpt:execution:vehicle:" + vehicleId, "outside the actor's scope"));
      throw new DomainException(ErrorCode.FORBIDDEN, "Vehicle " + vehicleId + " is not yours on " + day);
    }
    return signals.listen(Set.of(), Set.of(), Set.of(vehicleId), listener);
  }
}
