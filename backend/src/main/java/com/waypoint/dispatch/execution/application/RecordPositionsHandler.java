package com.waypoint.dispatch.execution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.domain.PositionFix;
import com.waypoint.dispatch.execution.domain.PositionPolicy;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcPositions;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandHandler;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stores a batch of fixes from the driver's phone (R-EXE-18 to R-EXE-20).
 *
 * <p>The driver must be assigned to the vehicle on each point's own service
 * date, not today, so a trail recorded offline yesterday still lands and a
 * driver can never write for a day they did not drive (EXE-LOC-04). No event is
 * published: a fix a minute per truck is not news for another module. Values
 * are never logged or echoed; metrics count points and lag only.
 */
@Component
public class RecordPositionsHandler implements CommandHandler {
  private final JdbcDeliveryRepository deliveries;
  private final JdbcPositions positions;
  private final ReferenceQuery reference;
  private final Metrics metrics;
  private final Clock clock;
  private final Database database;
  private final PositionSignals signals;
  private final SecureRandom random = new SecureRandom();

  public RecordPositionsHandler(
      JdbcDeliveryRepository deliveries, JdbcPositions positions, ReferenceQuery reference, Metrics metrics,
      Clock clock, Database database, PositionSignals signals) {
    this.deliveries = deliveries;
    this.positions = positions;
    this.reference = reference;
    this.metrics = metrics;
    this.clock = clock;
    this.database = database;
    this.signals = signals;
  }

  @Override
  public String kind() {
    return ExecutionCommands.RECORD_POSITIONS;
  }

  @Override
  public String action() {
    return ExecutionCommands.RECORD_POSITIONS;
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.EXECUTION;
  }

  @Override
  public String resource(Command command) {
    return ExecutionMessages.vehicleResource(command);
  }

  @Override
  public Object handle(Actor actor, Command command) {
    CommandPayload payload = CommandPayload.of(command);
    String vehicleId = payload.requiredText("vehicleId");
    UUID tripId = payload.optionalUuid("tripId");
    Instant now = clock.now();
    List<PositionFix> accepted;
    try {
      accepted = PositionPolicy.accept(points(command.payload().get("points")), now);
    } catch (DomainException e) {
      String detail = e.getMessage() == null ? "" : e.getMessage();
      metrics.increment("waypoint.execution.positions_rejected", "reason",
          detail.substring(detail.lastIndexOf(' ') + 1));
      throw e;
    }

    var vehicle = reference.vehicle(vehicleId, null);
    if (vehicle.isEmpty()) {
      throw new DomainException(ErrorCode.FORBIDDEN, "You are not assigned to this vehicle on that day");
    }
    LocalDate tripDate = tripId == null ? null : positions.tripDate(tripId, vehicleId);
    if (tripId != null && tripDate == null) {
      throw new DomainException(ErrorCode.FORBIDDEN, "That trip is not this vehicle's");
    }

    int stored = 0;
    String depotCode = vehicle.get().depotCode();
    Set<LocalDate> storedDays = new HashSet<>();
    for (PositionFix fix : accepted) {
      LocalDate day = fix.recordedAt().atZone(Clock.OPERATING_ZONE).toLocalDate();
      if (!deliveries.mayRecordFor(vehicleId, day)) {
        throw new DomainException(ErrorCode.FORBIDDEN, "You are not assigned to this vehicle on that day");
      }
      UUID trip = day.equals(tripDate) ? tripId : null;
      if (positions.insert(UuidV7.generate(now, random), vehicleId, depotCode, day, trip,
          actor.userId(), fix, PositionPolicy.lowQuality(fix), now, command.commandId())) {
        stored++;
        storedDays.add(day);
      }
    }
    // The live map hears of it once the fixes are committed, never before (R-EXE-23).
    if (!storedDays.isEmpty()) {
      database.afterCommit(() -> storedDays.forEach(day -> signals.changed(depotCode, day)));
    }
    metrics.count("waypoint.execution.positions_accepted", stored);
    metrics.count("waypoint.execution.positions_duplicate", accepted.size() - stored);
    if (!accepted.isEmpty()) {
      metrics.record("waypoint.execution.positions_lag",
          Math.max(0, Duration.between(accepted.get(0).recordedAt(), now).toMillis()));
    }
    return Map.of("vehicleId", vehicleId, "received", accepted.size(), "stored", stored);
  }

  private static List<PositionFix> points(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid position batch: points", List.of("R-EXE-18"));
    }
    List<PositionFix> fixes = new ArrayList<>();
    for (JsonNode point : node) {
      try {
        fixes.add(new PositionFix(
            Instant.parse(point.path("recordedAt").asText()),
            decimal(point, "latitude"), decimal(point, "longitude"), decimal(point, "accuracyM"),
            decimal(point, "headingDeg"), decimal(point, "speedKmh")));
      } catch (DateTimeParseException | NumberFormatException e) {
        throw new DomainException(
            ErrorCode.VALIDATION_FAILED, "Invalid position batch: point", List.of("R-EXE-18"));
      }
    }
    return fixes;
  }

  private static BigDecimal decimal(JsonNode point, String field) {
    JsonNode value = point.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isNumber()) throw new NumberFormatException(field);
    return value.decimalValue();
  }
}
