package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.loading.contract.LoadingQuery;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestLineView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ReadyTripView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ShortfallView;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingReads;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Reading dock work. Contract methods read as the ambient actor of the calling
 * transaction; web methods as the signed-in actor. Either way row-level
 * security decides the rows.
 *
 * <p>A list for a depot outside the actor's scope is a 403 with an audit row,
 * never an empty list that reads as "no trips tonight" (SEC-04).
 */
@Component
public class LoadingDataQuery implements LoadingQuery {
  public static final String READ = "loading:Read";

  private final Database database;
  private final JdbcLoadingReads reads;
  private final ReferenceQuery reference;
  private final AuditLog audit;

  public LoadingDataQuery(
      Database database, JdbcLoadingReads reads, ReferenceQuery reference, AuditLog audit) {
    this.database = database;
    this.reads = reads;
    this.reference = reference;
    this.audit = audit;
  }

  // ---- contract: as the ambient actor ----------------------------------------

  @Override
  public Optional<ManifestView> manifest(UUID tripId) {
    return read(ambient(), () -> manifestOf(tripId));
  }

  @Override
  public Optional<ManifestLineView> orderLine(UUID tripId, UUID orderId) {
    return read(ambient(), () -> lineOf(tripId, orderId));
  }

  @Override
  public List<ReadyTripView> readyTrips(String depotCode, LocalDate serviceDate) {
    return read(ambient(), () -> boardOf(depotCode, serviceDate));
  }

  @Override
  public List<ShortfallView> openShortfalls(String depotCode) {
    return read(ambient(), () -> shortfallsOf(depotCode, true));
  }

  // ---- web: as the signed-in actor -------------------------------------------

  public ManifestView manifest(Actor actor, UUID tripId) {
    return read(actor.userId(), () -> manifestOf(tripId))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No trip " + tripId + " at your depot"));
  }

  public List<ReadyTripView> readyTrips(Actor actor, String depotCode, LocalDate serviceDate) {
    requireDepot(actor, depotCode);
    return read(actor.userId(), () -> boardOf(depotCode, serviceDate));
  }

  public List<ShortfallView> shortfalls(Actor actor, String depotCode, boolean openOnly) {
    requireDepot(actor, depotCode);
    return read(actor.userId(), () -> shortfallsOf(depotCode, openOnly));
  }

  // ---- internals ---------------------------------------------------------------

  private Optional<ManifestView> manifestOf(UUID tripId) {
    var trip = reads.trip(tripId);
    if (trip == null) {
      return Optional.empty();
    }
    int version = ((Number) trip.get("plan_version")).intValue();
    return Optional.of(
        LoadingViewMapper.manifest(trip, reads.stops(tripId, version), reads.items(tripId, version), reference));
  }

  private Optional<ManifestLineView> lineOf(UUID tripId, UUID orderId) {
    Map<String, Object> stop = reads.orderStop(tripId, orderId);
    if (stop == null) {
      return Optional.empty();
    }
    int version = ((Number) stop.get("plan_version")).intValue();
    return Optional.of(LoadingViewMapper.line(0, stop, reads.orderItems(tripId, version, orderId), reference));
  }

  private List<ReadyTripView> boardOf(String depotCode, LocalDate serviceDate) {
    return reads.readyTrips(depotCode, serviceDate).stream().map(LoadingViewMapper::readyTrip).toList();
  }

  private List<ShortfallView> shortfallsOf(String depotCode, boolean openOnly) {
    return reads.shortfalls(depotCode, openOnly).stream().map(LoadingViewMapper::shortfall).toList();
  }

  private UUID ambient() {
    return database.ambientActor().orElse(null);
  }

  private <T> T read(UUID actorId, Supplier<T> work) {
    return database.readAs(ModuleRole.LOADING, actorId, work);
  }

  /**
   * Policy allowed loading:Read; this is the scope half of "policy AND scope".
   * The denial is audited after the read-only check ends.
   */
  private void requireDepot(Actor actor, String depotCode) {
    boolean inScope =
        read(
            actor.userId(),
            () -> Boolean.TRUE.equals(
                database.queryOne("SELECT app.actor_has_depot(?) AS ok", depotCode).get("ok")));
    if (!inScope) {
      String resource = "wpt:loading:depot:" + depotCode;
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }
}
