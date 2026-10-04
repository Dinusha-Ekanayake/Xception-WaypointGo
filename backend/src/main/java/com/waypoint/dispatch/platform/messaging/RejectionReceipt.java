package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.error.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A rejected command, stored as its receipt so a retry gets the same answer
 * instead of running the decision again.
 *
 * <p>A client that lost the response to a rejection retries with the same command
 * id. Re-running could change the outcome for a reason that has nothing to do with
 * the command: another user's change landed in between and the second run is
 * accepted, or refused for a different reason. A device replaying an offline
 * queue would then be told two different things about one write. Storing the
 * rejection makes "the answer to command X" one fact.
 *
 * <p>Only deterministic rejections are stored. A {@code FORBIDDEN} answer is not:
 * a permission granted a minute later must work on the retry. Neither are rate
 * limits, timeouts or an unavailable dependency, which are exactly the failures a
 * retry is meant to get past.
 */
final class RejectionReceipt {
  /** Mirrors {@code ApiExceptionHandler.statusFor}, which platform/web owns. */
  private static final Set<ErrorCode> RECORDED =
      Set.of(
          ErrorCode.VALIDATION_FAILED,
          ErrorCode.CONSTRAINT_VIOLATED,
          ErrorCode.CONFLICT,
          ErrorCode.VERSION_CONFLICT,
          ErrorCode.NOT_FOUND);

  /** The lowest status stored for a rejection; a success is always below it. */
  static final int FIRST_REJECTION_STATUS = 400;

  private RejectionReceipt() {}

  static boolean recordable(ErrorCode code) {
    return RECORDED.contains(code);
  }

  static int status(ErrorCode code) {
    return switch (code) {
      case VALIDATION_FAILED -> 422;
      case NOT_FOUND -> 404;
      case CONFLICT, VERSION_CONFLICT, CONSTRAINT_VIOLATED -> 409;
      default -> throw new IllegalArgumentException(code + " is not stored as a receipt");
    };
  }

  static String toJson(ObjectMapper mapper, DomainException rejection) {
    ObjectNode body = mapper.createObjectNode();
    body.put("code", rejection.code().name());
    body.put("message", rejection.getMessage());
    ArrayNode violations = body.putArray("violations");
    for (Violation v : rejection.violations()) {
      ObjectNode node = violations.addObject();
      node.put("rule", v.rule());
      if (v.field() != null) {
        node.put("field", v.field());
      }
      node.put("message", v.message());
    }
    if (!rejection.extensions().isEmpty()) {
      // What the client needs to recover, such as per-line availability, so a retry of
      // the same command id is told exactly what the first attempt was told.
      body.set("extensions", mapper.valueToTree(rejection.extensions()));
    }
    return body.toString();
  }

  /** The rejection a stored body describes, marked so the bus does not store it again. */
  static DomainException rebuild(ObjectMapper mapper, Object storedBody) {
    try {
      JsonNode body = mapper.readTree(String.valueOf(storedBody));
      List<Violation> violations = new ArrayList<>();
      for (JsonNode v : body.path("violations")) {
        violations.add(
            new Violation(
                v.path("rule").asText(),
                v.hasNonNull("field") ? v.get("field").asText() : null,
                v.path("message").asText()));
      }
      DomainException stored =
          new Stored(
              ErrorCode.valueOf(body.path("code").asText()), body.path("message").asText(), violations);
      if (body.path("extensions").isObject()) {
        body.get("extensions")
            .fields()
            .forEachRemaining(e -> stored.with(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
      }
      return stored;
    } catch (Exception e) {
      throw new IllegalStateException("Stored rejection is not readable", e);
    }
  }

  /** A rejection answered from a receipt rather than decided now. */
  static final class Stored extends DomainException {
    Stored(ErrorCode code, String message, List<Violation> violations) {
      super(code, message, violations, null);
    }
  }
}
