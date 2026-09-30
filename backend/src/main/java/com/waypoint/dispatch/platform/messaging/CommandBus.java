package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Runs exactly one business decision, atomically.
 *
 * <p>The sequence is fixed and is the reason this class exists rather than each
 * module repeating it: authorize, check for a replay, run the handler, then
 * commit the state change, the receipt and the audit row together. Nothing here
 * decides business rules; it decides that a decision happens once.
 */
@Component
public class CommandBus {
  private final Map<String, CommandHandler> handlers;
  private final Optional<CommandAuthorizer> authorizer;
  private final IdempotencyGuard idempotency;
  private final Database database;
  private final AuditLog audit;
  private final Metrics metrics;
  private final ObjectMapper mapper;

  public CommandBus(
      List<CommandHandler> handlers,
      Optional<CommandAuthorizer> authorizer,
      IdempotencyGuard idempotency,
      Database database,
      AuditLog audit,
      Metrics metrics,
      ObjectMapper mapper) {
    // Duplicate kinds fail here, at startup, rather than routing a command to
    // whichever handler happened to be registered last.
    this.handlers =
        handlers.stream().collect(Collectors.toMap(CommandHandler::kind, Function.identity()));
    this.authorizer = authorizer;
    this.idempotency = idempotency;
    this.database = database;
    this.audit = audit;
    this.metrics = metrics;
    this.mapper = mapper;
  }

  public CommandResult dispatch(Actor actor, Command command) {
    CommandHandler handler = handlers.get(command.kind());
    if (handler == null) {
      throw new DomainException(ErrorCode.NOT_FOUND, "No handler for command " + command.kind());
    }

    // Fail closed. A system with no authorizer wired refuses work rather than
    // performing it unchecked.
    //
    // Spelt out rather than chained through Optional.map: mapping to null
    // collapses to an empty Optional, so the fail-closed default fired on every
    // ALLOWED command and the bus denied everything with an authorizer present.
    // Nothing caught it because nothing had ever dispatched a command.
    String resource = handler.resource(command);
    String denial =
        authorizer.isPresent()
            ? authorizer.get().denyReason(actor, handler.action(), resource, command).orElse(null)
            : "Authorization is not configured";
    if (denial != null) {
      metrics.increment("waypoint.command.denied", "kind", command.kind());
      // The decision point already audited a policy denial; this covers the
      // fail-closed case where no authorizer exists at all.
      if (authorizer.isEmpty()) {
        audit.recordStandalone(
            AuditEntry.denied(actor.userId(), actor.deviceId(), handler.action(), resource, denial));
      }
      throw new DomainException(ErrorCode.FORBIDDEN, denial);
    }

    String fingerprint = idempotency.fingerprint(command.kind(), command.payload());

    try {
      return execute(actor, command, handler, resource, fingerprint);
    } catch (DomainException e) {
      // Scope is decided inside the transaction, because only there does row
      // level security see the actor. A denial raised there rolls back with
      // everything else, its audit row included, so it is recorded again here,
      // after the rollback, in a transaction of its own.
      if (e.code() == ErrorCode.FORBIDDEN) {
        metrics.increment("waypoint.command.denied", "kind", command.kind());
        audit.recordStandalone(
            AuditEntry.denied(
                actor.userId(), actor.deviceId(), handler.action(), resource, e.getMessage()));
      }
      throw e;
    }
  }

  private CommandResult execute(
      Actor actor, Command command, CommandHandler handler, String resource, String fingerprint) {
    return database.asModule(
        handler.moduleRole(),
        actor.userId(),
        () -> {
          Map<String, Object> receipt =
              database.queryOne(
                  "SELECT payload_hash, result_body FROM integration.command_receipts"
                      + " WHERE command_id = ? AND actor_id = ?",
                  command.commandId(),
                  actor.userId());
          Optional<Object> replay =
              idempotency.replayOrReject(command.commandId(), fingerprint, receipt);
          if (replay.isPresent()) {
            metrics.increment("waypoint.command.replayed", "kind", command.kind());
            return CommandResult.replayed(fromJson(replay.get()));
          }

          Object value = handler.handle(actor, command);

          database.update(
              """
              INSERT INTO integration.command_receipts
                  (command_id, actor_id, command_kind, payload_hash, result_status, result_body)
              VALUES (?, ?, ?, ?, ?, ?::jsonb)
              """,
              command.commandId(),
              actor.userId(),
              command.kind(),
              fingerprint,
              200,
              toJson(value));
          audit.record(
              AuditEntry.allowed(
                  actor.userId(), actor.deviceId(), handler.action(), resource, "command applied"));
          metrics.increment("waypoint.command.applied", "kind", command.kind());
          return CommandResult.executed(value);
        });
  }

  private String toJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Command result is not serialisable", e);
    }
  }

  /**
   * A jsonb column comes back as driver-specific text, so a replay would
   * otherwise answer with a string of JSON where the first call answered with an
   * object. A retry must be indistinguishable from the original, including its
   * shape.
   */
  private Object fromJson(Object stored) {
    try {
      String json = String.valueOf(stored);
      return json.isBlank() || "null".equals(json) ? null : mapper.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException("Stored command result is not readable JSON", e);
    }
  }
}
