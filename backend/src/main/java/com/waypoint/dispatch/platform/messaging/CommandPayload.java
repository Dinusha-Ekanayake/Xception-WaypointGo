package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Reads a command's fields, and says which field was wrong when one is.
 *
 * <p>Here rather than in each handler because the alternative is every module
 * reimplementing it, and a payload error reading as a 500 out of
 * {@code UUID.fromString} the one time someone forgets. A malformed envelope is
 * the client's mistake and is reported as one, with the field named.
 *
 * <p>Absent, null and blank are the same thing. A client that sends
 * {@code "reason": ""} means it has no reason.
 */
public final class CommandPayload {
  private final JsonNode node;

  private CommandPayload(JsonNode node) {
    this.node = node;
  }

  public static CommandPayload of(Command command) {
    return new CommandPayload(command == null ? null : command.payload());
  }

  /** Null when absent, so a caller can tell "leave it alone" from "set it to this". */
  public String text(String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    String value = node.get(field).asText().trim();
    return value.isEmpty() ? null : value;
  }

  public String requiredText(String field) {
    String value = text(field);
    if (value == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is required");
    }
    return value;
  }

  /** Never trimmed and never reported back: a password is neither displayed nor logged. */
  public String secret(String field) {
    if (node == null || !node.hasNonNull(field)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is required");
    }
    return node.get(field).asText();
  }

  public UUID uuid(String field) {
    UUID value = optionalUuid(field);
    if (value == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is required");
    }
    return value;
  }

  public UUID optionalUuid(String field) {
    String value = text(field);
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is not a uuid: " + value);
    }
  }

  public LocalDate date(String field) {
    LocalDate value = optionalDate(field);
    if (value == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is required");
    }
    return value;
  }

  public LocalDate optionalDate(String field) {
    String value = text(field);
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, field + " must be a date as yyyy-mm-dd, not " + value);
    }
  }

  public boolean flag(String field, boolean whenAbsent) {
    if (node == null || !node.hasNonNull(field)) {
      return whenAbsent;
    }
    return node.get(field).asBoolean(whenAbsent);
  }
}
