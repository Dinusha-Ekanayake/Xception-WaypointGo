package com.waypoint.dispatch.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.platform.messaging.CommandResult;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one door every mutation goes through.
 *
 * <p>There is deliberately no endpoint per command. A single envelope is what
 * lets an offline device queue a write, replay it verbatim hours later, and get
 * the original answer rather than a second execution. Adding a command adds a
 * handler, not a route.
 *
 * <p>Thin by contract: resolve the caller, validate the envelope, dispatch. It
 * decides nothing, and in particular it does not decide authorization or which
 * database role runs the work. The bus does the first, the handler declares the
 * second.
 */
@RestController
@RequestMapping("/api/commands")
public class CommandController {
  private final CommandBus bus;
  private final Optional<ActorResolver> actors;

  public CommandController(CommandBus bus, Optional<ActorResolver> actors) {
    this.bus = bus;
    this.actors = actors;
  }

  /** The wire envelope, mirroring {@code frontend/src/shared/api/commands.ts}. */
  public record CommandRequest(
      String commandId,
      String kind,
      Long expectedVersion,
      JsonNode payload,
      String clientRecordedAt,
      String actingUserId) {}

  /**
   * What the caller gets back.
   *
   * @param replayed true when a stored receipt answered instead of the handler
   *     running again, so a client can tell "done" from "done just now"
   */
  public record CommandAck(String commandId, String kind, boolean replayed, Object result) {}

  @PostMapping
  public CommandAck submit(@RequestBody CommandRequest body, HttpServletRequest request) {
    Command command = toCommand(body);
    Actor actor =
        actors
            .flatMap(resolver -> resolver.resolveCommand(request, command))
            .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHENTICATED, "Not signed in"));

    CommandResult result =
        bus.dispatch(actor, command, (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
    return new CommandAck(
        command.commandId().toString(), command.kind(), result.replayed(), result.value());
  }

  /**
   * A malformed envelope is the client's mistake, so it reads as one. Parsing it
   * by hand rather than through bean validation keeps the failure a
   * {@code 422} with the field named, instead of a 500 out of
   * {@code UUID.fromString}.
   */
  private static Command toCommand(CommandRequest body) {
    if (body == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "A command envelope is required");
    }
    if (body.kind() == null || body.kind().isBlank()) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "kind is required");
    }
    return new Command(
        uuid(body.commandId()),
        body.kind(),
        body.expectedVersion(),
        body.payload(),
        instant(body.clientRecordedAt()),
        optionalUuid(body.actingUserId(), "actingUserId"));
  }

  private static UUID optionalUuid(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, field + " is not a uuid");
    }
  }

  private static UUID uuid(String value) {
    if (value == null || value.isBlank()) {
      // Server-minted ids would defeat the point: the id is the idempotency key
      // and only the client knows that this is the same write as last time.
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "commandId is required and must be client generated");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "commandId is not a uuid: " + value);
    }
  }

  /** Forensic only. An unreadable device clock must not cost the caller their write. */
  private static Instant instant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
