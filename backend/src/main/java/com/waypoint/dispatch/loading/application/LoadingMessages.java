package com.waypoint.dispatch.loading.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.loading.domain.LoadingSession;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository;
import com.waypoint.dispatch.loading.infrastructure.JdbcLoadingRepository.TripHeader;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandPayload;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What every Loading handler does the same way: read the trip, refuse a stale
 * version early, and parse the fields the platform payload helper has no type
 * for. The write is still the authoritative version check.
 */
final class LoadingMessages {
  private LoadingMessages() {}

  /** A trip and its session on the current plan version, as the actor may see it. */
  record Loaded(TripHeader trip, LoadingSession session) {}

  static String resource(Command command) {
    UUID id = CommandPayload.of(command).optionalUuid("tripId");
    return id == null ? null : "wpt:loading:trip:" + id;
  }

  static long expectedVersion(Command command) {
    if (command.expectedVersion() == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED,
          "expectedVersion is required: send the trip's rowVersion from the load sheet");
    }
    return command.expectedVersion();
  }

  /**
   * Not found covers both a trip that does not exist and one outside the actor's
   * depots: row-level security hides it, and saying which would leak that it exists.
   */
  static Loaded load(JdbcLoadingRepository trips, UUID tripId, long expected) {
    TripHeader header =
        trips.header(tripId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No trip " + tripId + " at your depot"));
    LoadingSession session =
        trips.session(tripId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No trip " + tripId + " at your depot"));
    if (session.rowVersion() != expected) {
      throw new DomainException(
          ErrorCode.VERSION_CONFLICT,
          "The load sheet changed on another device. Reload it and check again.");
    }
    return new Loaded(header, session);
  }

  static Optional<Integer> optionalInt(Command command, String field) {
    JsonNode node = command.payload() == null ? null : command.payload().get(field);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    if (!node.canConvertToInt() || !node.isIntegralNumber()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " must be a whole number");
    }
    return Optional.of(node.asInt());
  }

  static int requiredInt(Command command, String field) {
    return optionalInt(command, field)
        .orElseThrow(() -> new DomainException(ErrorCode.VALIDATION_FAILED, field + " is required"));
  }

  static Optional<BigDecimal> optionalDecimal(Command command, String field) {
    JsonNode node = command.payload() == null ? null : command.payload().get(field);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    if (!node.isNumber()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " must be a number");
    }
    return Optional.of(node.decimalValue());
  }

  static boolean requiredFlag(Command command, String field) {
    JsonNode node = command.payload() == null ? null : command.payload().get(field);
    if (node == null || !node.isBoolean()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " must be true or false");
    }
    return node.asBoolean();
  }

  static CheckStatus status(Command command, String field) {
    String value = CommandPayload.of(command).requiredText(field);
    try {
      return CheckStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Unknown " + field + " " + value);
    }
  }

  /** The answer every handler returns, so the client can carry the version on. */
  static Map<String, Object> result(UUID tripId, long rowVersion, LoadingSession session) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("tripId", tripId.toString());
    out.put("rowVersion", rowVersion);
    out.put("status", session.status().name());
    return out;
  }
}
