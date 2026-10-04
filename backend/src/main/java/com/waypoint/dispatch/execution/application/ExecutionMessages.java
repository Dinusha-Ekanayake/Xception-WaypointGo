package com.waypoint.dispatch.execution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.Stamp;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What every Execution handler does the same way: find the stop the actor may
 * record for, refuse a stale version early, and parse the fields the platform
 * payload helper has no type for. The write is still the authoritative version
 * check.
 */
final class ExecutionMessages {
  /** One wording for absent and out of scope, so a denial never says which. */
  static final String OUT_OF_SCOPE = "This stop is not on a vehicle you are driving today";

  private ExecutionMessages() {}

  static String deliveryResource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("deliveryId");
    return id == null ? null : deliveryResource(id);
  }

  static String deliveryResource(UUID deliveryId) {
    return "wpt:execution:delivery:" + deliveryId;
  }

  static String vehicleResource(Command command) {
    String vehicle = CommandPayload.of(command).text("vehicleId");
    return vehicle == null ? null : "wpt:execution:vehicle:" + vehicle;
  }

  static long expectedVersion(Command command) {
    if (command.expectedVersion() == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "expectedVersion is required: send the stop's rowVersion from the run sheet");
    }
    return command.expectedVersion();
  }

  /**
   * The stop, if the actor drives its vehicle on its date (EXE-13). Scope is
   * refused before versions are compared, and the bus audits the denial after
   * the transaction rolls back.
   */
  static DeliveryRecord load(JdbcDeliveryRepository deliveries, UUID deliveryId, long expected) {
    DeliveryRecord record = loadForWrite(deliveries, deliveryId);
    if (record.rowVersion() != expected) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT,
          "This stop changed on another device or in the plan. Reload the run sheet.");
    }
    return record;
  }

  static DeliveryRecord loadForWrite(JdbcDeliveryRepository deliveries, UUID deliveryId) {
    DeliveryRecord record =
        deliveries.find(deliveryId).orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN, OUT_OF_SCOPE));
    if (!deliveries.mayRecordFor(record.vehicleId(), record.serviceDate())) {
      // Visible to a dispatcher or a store manager, who read and never record.
      throw new DomainException(ErrorCode.FORBIDDEN, OUT_OF_SCOPE);
    }
    return record;
  }

  static Stamp stamp(Actor actor, Command command, Instant now) {
    return new Stamp(actor.userId(), actor.deviceId(), now, command.clientRecordedAt());
  }

  static Optional<Integer> optionalInt(Command command, String field) {
    JsonNode node = command.payload() == null ? null : command.payload().get(field);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " must be a whole number");
    }
    return Optional.of(node.asInt());
  }

  static Optional<Instant> optionalInstant(Command command, String field) {
    String value = CommandPayload.of(command).text(field);
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(value));
    } catch (DateTimeParseException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, field + " must be an instant such as 2026-10-02T03:15:00Z");
    }
  }

  static String boundedText(Command command, String field, int limit) {
    String value = CommandPayload.of(command).requiredText(field);
    if (value.length() > limit) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is at most " + limit + " characters");
    }
    return value;
  }

  /** The answer every stop command returns, so the device can carry the version on. */
  static Map<String, Object> result(DeliveryRecord record, long rowVersion) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("deliveryId", record.deliveryId().toString());
    out.put("rowVersion", rowVersion);
    out.put("outcome", record.outcome().name());
    record.waitMinutes().ifPresent(m -> out.put("waitMinutes", m));
    record.lateMinutes().ifPresent(m -> out.put("lateMinutes", m));
    out.put("timingUncertain", record.timingUncertain());
    return out;
  }
}
