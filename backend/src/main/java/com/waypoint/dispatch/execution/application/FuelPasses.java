package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionViews.FuelPassStatusView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.FuelPassView;
import com.waypoint.dispatch.execution.domain.FuelPassRules;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcFuelPasses;
import com.waypoint.dispatch.execution.infrastructure.JdbcFuelPasses.Pass;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.ImageKind;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The vehicle's National Fuel Pass on the driver's phone (FF-06b).
 *
 * <p>The upload is binary, so it does not go through the command bus; it keeps
 * the bus's guarantees by other means. The pass id is minted by the uploading
 * screen and the content is addressed by SHA-256, so the same upload sent twice
 * is one pass and a different image under the same id is refused. The expected
 * version is the number of passes the vehicle has had, so two administrators
 * replacing one pass collide instead of the later silently winning. Replacing a
 * pass revokes the one before in the same transaction.
 *
 * <p>The image is a bearer credential. Only the driver assigned to the vehicle
 * today or tomorrow reads it, every read and every refusal is audited, and the
 * administrator's view never carries the image.
 */
@Component
public class FuelPasses {
  private final Database database;
  private final JdbcFuelPasses passes;
  private final JdbcDeliveryRepository deliveries;
  private final ReferenceQuery reference;
  private final AuditLog audit;
  private final Metrics metrics;
  private final Clock clock;

  public FuelPasses(
      Database database,
      JdbcFuelPasses passes,
      JdbcDeliveryRepository deliveries,
      ReferenceQuery reference,
      AuditLog audit,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.passes = passes;
    this.deliveries = deliveries;
    this.reference = reference;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** @param stored false when this exact pass was already here */
  public record Receipt(String vehicleId, UUID passId, String sha256, int sizeBytes, long version, boolean stored) {}

  public Receipt upload(Actor actor, String vehicleId, UUID passId, long expectedVersion, byte[] content) {
    ImageKind image = FuelPassRules.requireImage(content);
    if (reference.vehicle(vehicleId, null).isEmpty()) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No vehicle " + vehicleId);
    }
    String sha256 = sha256(content);
    Instant now = clock.now();
    Receipt receipt =
        database.asModule(
            ModuleRole.EXECUTION,
            actor.userId(),
            () -> {
              Optional<Pass> existing = passes.byId(passId);
              if (existing.isPresent()) {
                Pass pass = existing.get();
                if (!pass.vehicleId().equals(vehicleId) || !pass.sha256().equals(sha256)) {
                  throw new DomainException(
                      ErrorCode.CONFLICT, "A different image is already stored under this pass id");
                }
                return new Receipt(vehicleId, passId, sha256, pass.sizeBytes(), passes.version(vehicleId), false);
              }
              long version = passes.version(vehicleId);
              if (version != expectedVersion) {
                throw new DomainException(
                    ErrorCode.VERSION_CONFLICT,
                    "The Fuel Pass of " + vehicleId + " changed since this screen loaded; reload and try again");
              }
              passes.revokeCurrent(vehicleId, actor.userId(), now, "replaced by a new pass");
              passes.insert(passId, vehicleId, content, image.contentType(), sha256, actor.userId(), now);
              audit.record(
                  AuditEntry.allowed(
                      actor.userId(), actor.deviceId(), ExecutionCommands.SET_FUEL_PASS, resource(vehicleId),
                      "uploaded pass " + passId));
              return new Receipt(vehicleId, passId, sha256, content.length, version + 1, true);
            });
    metrics.increment("waypoint.execution.fuel_pass", "outcome", receipt.stored() ? "uploaded" : "duplicate");
    return receipt;
  }

  /**
   * The pass of a vehicle the actor drives today or tomorrow. Anyone else is
   * refused and the refusal audited; a vehicle with no pass is not found, so the
   * phone says to ask the depot rather than show a code that will not scan.
   */
  public FuelPassView forDriver(Actor actor, String vehicleId) {
    LocalDate today = clock.now().atZone(Clock.OPERATING_ZONE).toLocalDate();
    record Read(boolean today, boolean tomorrow, Optional<Pass> pass) {}
    Read read =
        database.readAs(
            ModuleRole.EXECUTION,
            actor.userId(),
            () -> {
              boolean drivesToday = deliveries.mayRecordFor(vehicleId, today);
              boolean drivesTomorrow = deliveries.mayRecordFor(vehicleId, today.plusDays(1));
              return new Read(
                  drivesToday, drivesTomorrow,
                  drivesToday || drivesTomorrow ? passes.current(vehicleId) : Optional.empty());
            });
    if (!read.today() && !read.tomorrow()) {
      audit.recordStandalone(
          AuditEntry.denied(
              actor.userId(), actor.deviceId(), ExecutionCommands.READ_FUEL_PASS, resource(vehicleId),
              "not assigned to the vehicle today or tomorrow"));
      metrics.increment("waypoint.execution.fuel_pass", "outcome", "denied");
      throw new DomainException(ErrorCode.FORBIDDEN, "You are not assigned to this vehicle today or tomorrow");
    }
    Pass pass =
        read.pass()
            .orElseThrow(
                () -> new DomainException(
                    ErrorCode.NOT_FOUND, "No Fuel Pass has been added for " + vehicleId + " yet. Ask the depot."));
    audit.recordStandalone(
        AuditEntry.allowed(
            actor.userId(), actor.deviceId(), ExecutionCommands.READ_FUEL_PASS, resource(vehicleId),
            "read pass " + pass.passId()));
    metrics.increment("waypoint.execution.fuel_pass", "outcome", "read");
    return new FuelPassView(
        vehicleId, pass.passId(), pass.contentType(), pass.sha256(), Base64.getEncoder().encodeToString(pass.content()),
        FuelPassRules.validUntil(today, read.tomorrow()));
  }

  /** Every vehicle that has had a pass, for the administrator; never the image. */
  public List<FuelPassStatusView> statuses(Actor actor) {
    List<Map<String, Object>> rows = database.readAs(ModuleRole.EXECUTION, actor.userId(), passes::statuses);
    return rows.stream()
        .map(
            row ->
                new FuelPassStatusView(
                    (String) row.get("vehicle_id"),
                    ((Number) row.get("version")).longValue(),
                    Optional.ofNullable((UUID) row.get("pass_id")),
                    Optional.ofNullable((java.sql.Timestamp) row.get("uploaded_at")).map(java.sql.Timestamp::toInstant),
                    Optional.ofNullable((Number) row.get("size_bytes")).map(Number::intValue),
                    Optional.ofNullable((Number) row.get("row_version")).map(Number::longValue)))
        .toList();
  }

  static String resource(String vehicleId) {
    return "wpt:execution:vehicle:" + vehicleId;
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
