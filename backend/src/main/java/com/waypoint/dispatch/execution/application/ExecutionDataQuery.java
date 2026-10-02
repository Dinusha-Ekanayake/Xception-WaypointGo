package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.ProofView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.StopActualView;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcExecutionReads;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Reading what happened on the road. Contract methods read as the ambient actor
 * of the calling transaction; web methods as the signed-in actor. Either way
 * row-level security decides the rows: a driver's vehicle on its date, a
 * dispatcher's depots, a store manager's outlets.
 *
 * <p>Asking for a depot or an outlet outside the actor's scope is a 403 with an
 * audit row, never an empty list that reads as "nothing on the road" (SEC-04).
 */
@Component
public class ExecutionDataQuery implements ExecutionQuery {
  public static final String READ = "delivery:Read";

  private final Database database;
  private final JdbcExecutionReads reads;
  private final JdbcDeliveryRepository deliveries;
  private final ProofLinks links;
  private final AuditLog audit;

  public ExecutionDataQuery(
      Database database,
      JdbcExecutionReads reads,
      JdbcDeliveryRepository deliveries,
      ProofLinks links,
      AuditLog audit) {
    this.database = database;
    this.reads = reads;
    this.deliveries = deliveries;
    this.links = links;
    this.audit = audit;
  }

  // ---- contract: as the ambient actor ----------------------------------------

  @Override
  public Optional<RunSheetView> runSheet(String vehicleId, LocalDate serviceDate) {
    return read(ambient(), () -> sheetOf(vehicleId, serviceDate));
  }

  @Override
  public Optional<DeliveryRecordView> deliveryRecord(UUID deliveryId) {
    return read(ambient(), () -> Optional.ofNullable(reads.record(deliveryId)).map(ExecutionViewMapper::view));
  }

  @Override
  public Optional<DeliveryRecordView> deliveryForOrder(UUID orderId) {
    return read(ambient(), () -> Optional.ofNullable(reads.latestForOrder(orderId)).map(ExecutionViewMapper::view));
  }

  // ---- web: as the signed-in actor -------------------------------------------

  /** The run sheets of every vehicle the actor drives on that date; normally one. */
  public List<RunSheetView> myRunSheets(Actor actor, LocalDate serviceDate) {
    return read(
        actor.userId(),
        () ->
            reads.vehiclesDrivenOn(serviceDate).stream()
                .map(vehicle -> sheetOf(vehicle, serviceDate).orElse(new RunSheetView(vehicle, serviceDate, List.of())))
                .toList());
  }

  /** Every vehicle on the road from one depot, for the dispatcher's live view. */
  public List<RunSheetView> runSheetsOfDepot(Actor actor, String depotCode, LocalDate serviceDate) {
    require(actor, "wpt:execution:depot:" + depotCode, () -> reads.depotInScope(depotCode));
    return read(actor.userId(), () -> ExecutionViewMapper.sheets(reads.stopsOfDepot(depotCode, serviceDate)));
  }

  /** What is coming to, or has reached, one outlet on a day. */
  public List<DeliveryRecordView> deliveriesForOutlet(Actor actor, String outletId, LocalDate serviceDate) {
    require(actor, "wpt:execution:outlet:" + outletId, () -> reads.outletInScope(outletId));
    return read(
        actor.userId(),
        () -> reads.stopsOfOutlet(outletId, serviceDate).stream().map(ExecutionViewMapper::view).toList());
  }

  public DeliveryRecordView delivery(Actor actor, UUID deliveryId) {
    return read(actor.userId(), () -> Optional.ofNullable(reads.record(deliveryId)).map(ExecutionViewMapper::view))
        .orElseThrow(() -> notFound(deliveryId));
  }

  public DeliveryRecordView deliveryForOrder(Actor actor, UUID orderId) {
    return read(actor.userId(), () -> Optional.ofNullable(reads.latestForOrder(orderId)).map(ExecutionViewMapper::view))
        .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No delivery for that order within your scope"));
  }

  /** The delivery's proof, with links that open its artifacts for a few minutes. */
  public ProofView proof(Actor actor, UUID deliveryId) {
    return read(
        actor.userId(),
        () -> {
          if (reads.record(deliveryId) == null) {
            throw notFound(deliveryId);
          }
          Map<String, Object> row = reads.proof(deliveryId);
          if (row == null) {
            throw new DomainException(ErrorCode.NOT_FOUND, "No proof has been captured for this delivery");
          }
          Instant expires = links.expiry();
          UUID photo = (UUID) row.get("photo_attachment_id");
          UUID signature = (UUID) row.get("signature_attachment_id");
          boolean photoStored = photo != null && deliveries.attachment(photo).isPresent();
          boolean signatureStored = signature != null && deliveries.attachment(signature).isPresent();
          // Past retention (P-14) an artifact is on record but no longer held: no link, and not pending.
          boolean photoHeld = photoStored && deliveries.isHeld(photo);
          boolean signatureHeld = signatureStored && deliveries.isHeld(signature);
          return new ProofView(
              (UUID) row.get("proof_id"),
              deliveryId,
              Optional.ofNullable((String) row.get("recipient_name")),
              Optional.ofNullable((String) row.get("fallback_reason")),
              Boolean.TRUE.equals(row.get("low_evidence")),
              ((Timestamp) row.get("captured_at")).toInstant(),
              photoHeld ? Optional.of(links.urlFor(photo, expires)) : Optional.empty(),
              photo != null && !photoStored,
              signatureHeld ? Optional.of(links.urlFor(signature, expires)) : Optional.empty(),
              signature != null && !signatureStored,
              expires);
        });
  }

  /** The vehicles the actor is assigned to drive on a date, whether or not a trip has left yet. */
  public List<String> myVehicles(Actor actor, LocalDate serviceDate) {
    return read(actor.userId(), () -> reads.vehiclesDrivenOn(serviceDate));
  }

  // ---- internals ---------------------------------------------------------------

  private Optional<RunSheetView> sheetOf(String vehicleId, LocalDate serviceDate) {
    return ExecutionViewMapper.sheets(reads.stopsOfVehicle(vehicleId, serviceDate)).stream().findFirst();
  }

  private static DomainException notFound(UUID deliveryId) {
    return new DomainException(ErrorCode.NOT_FOUND, "No delivery " + deliveryId + " within your scope");
  }

  @Override
  public com.waypoint.dispatch.shared.domain.Page<StopActualView> actuals(
      String depotCode, LocalDate from, LocalDate to, Optional<String> cursor, int limit) {
    int size = com.waypoint.dispatch.shared.domain.Page.limit(limit);
    List<String> key = com.waypoint.dispatch.shared.domain.Cursor.decode(cursor.orElse(null), 2);
    LocalDate afterDate = null;
    UUID afterId = null;
    if (!key.isEmpty()) {
      try {
        afterDate = LocalDate.parse(key.get(0));
        afterId = UUID.fromString(key.get(1));
      } catch (RuntimeException e) {
        throw com.waypoint.dispatch.shared.domain.Cursor.invalid();
      }
    }
    LocalDate fromDate = afterDate;
    UUID fromId = afterId;
    List<StopActualView> rows =
        read(ambient(), () -> reads.actuals(depotCode, from, to, fromDate, fromId, size + 1)).stream()
            .map(ExecutionDataQuery::actual)
            .toList();
    return com.waypoint.dispatch.shared.domain.Page.fromOverfetch(
        rows,
        size,
        a -> com.waypoint.dispatch.shared.domain.Cursor.encode(a.serviceDate().toString(), a.deliveryId().toString()));
  }

  /** Service is from its start to completion; the wait before the window is never part of it (EXE-18). */
  private static StopActualView actual(Map<String, Object> row) {
    Optional<Instant> started = timestamp(row.get("service_started_at"));
    Optional<Instant> completed = timestamp(row.get("completed_at"));
    Optional<java.math.BigDecimal> service =
        started.isPresent() && completed.isPresent()
            ? Optional.of(
                java.math.BigDecimal.valueOf(java.time.Duration.between(started.get(), completed.get()).toSeconds())
                    .divide(java.math.BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP))
            : Optional.empty();
    return new StopActualView(
        (UUID) row.get("delivery_id"),
        (UUID) row.get("order_id"),
        (String) row.get("outlet_id"),
        (String) row.get("depot_code"),
        (String) row.get("vehicle_id"),
        ((java.sql.Date) row.get("service_date")).toLocalDate(),
        ((Number) row.get("stop_sequence")).intValue(),
        ((java.sql.Time) row.get("planned_arrival")).toLocalTime(),
        ((java.sql.Time) row.get("window_open")).toLocalTime(),
        ((java.sql.Time) row.get("window_close")).toLocalTime(),
        timestamp(row.get("arrived_at")),
        started,
        completed,
        Optional.ofNullable((Number) row.get("wait_minutes")).map(Number::intValue),
        service,
        Optional.ofNullable((Number) row.get("late_minutes")).map(Number::intValue),
        com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome.valueOf(
            ((String) row.get("outcome")).toUpperCase(java.util.Locale.ROOT)),
        (Boolean) row.get("timing_uncertain"));
  }

  private static Optional<Instant> timestamp(Object value) {
    return Optional.ofNullable((Timestamp) value).map(Timestamp::toInstant);
  }

  private UUID ambient() {
    return database.ambientActor().orElse(null);
  }

  private <T> T read(UUID actorId, Supplier<T> work) {
    return database.readAs(ModuleRole.EXECUTION, actorId, work);
  }

  /**
   * Policy allowed delivery:Read; this is the scope half of "policy AND scope".
   * The denial is audited after the read-only check ends.
   */
  private void require(Actor actor, String resource, Supplier<Boolean> inScope) {
    if (!read(actor.userId(), inScope)) {
      String reason = "outside the actor's scope";
      audit.recordStandalone(AuditEntry.denied(actor.userId(), actor.deviceId(), READ, resource, reason));
      throw new DomainException(ErrorCode.FORBIDDEN, resource + " is " + reason);
    }
  }
}
