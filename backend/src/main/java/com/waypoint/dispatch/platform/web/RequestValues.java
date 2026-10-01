package com.waypoint.dispatch.platform.web;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.error.Violation;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Parses request values a controller receives as text.
 *
 * <p>One place, so an unparseable id is always a {@code 400} naming the field
 * rather than an {@code IllegalArgumentException} that surfaces as a 500. The
 * offending value is never echoed back: it is attacker-controlled text and the
 * detail is logged and displayed.
 */
public final class RequestValues {
  private RequestValues() {}

  /** @return the id, or {@code null} when the value is absent */
  public static UUID optionalUuid(String field, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw invalid(field, "must be a UUID");
    }
  }

  public static UUID uuid(String field, String value) {
    UUID parsed = optionalUuid(field, value);
    if (parsed == null) {
      throw invalid(field, "is required");
    }
    return parsed;
  }

  /** @return the date, or {@code null} when the value is absent */
  public static LocalDate optionalDate(String field, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw invalid(field, "must be a date as yyyy-mm-dd");
    }
  }

  public static LocalDate date(String field, String value) {
    LocalDate parsed = optionalDate(field, value);
    if (parsed == null) {
      throw invalid(field, "is required");
    }
    return parsed;
  }

  private static DomainException invalid(String field, String problem) {
    String message = field + " " + problem;
    return DomainException.withViolations(
        ErrorCode.BAD_REQUEST, message, List.of(Violation.onField("request:format", field, message)));
  }
}
