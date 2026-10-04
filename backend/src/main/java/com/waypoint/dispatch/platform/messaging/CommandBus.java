package com.waypoint.dispatch.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.audit.AuditContext;
import com.waypoint.dispatch.platform.audit.AuditEntry;
import com.waypoint.dispatch.platform.audit.AuditLog;
import com.waypoint.dispatch.platform.audit.AuditRedactor;
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
 * module repeating it: authorize, open the transaction and authorize again inside
 * it, check for a replay, run the handler, then commit the state change, the
 * receipt and the audit row together. Nothing here
 * decides business rules; it decides that a decision happens once.
 *
 * <p>Every answer is a receipt, including a rejection: a retry of a command that
 * was refused for a rule it broke gets the same refusal rather than a fresh run
 * (see {@link RejectionReceipt}). A permission denial is the exception, because
 * the permission may have been granted by the time the retry arrives.
 */
@Component
public class CommandBus {
  /** Characters of redacted JSON kept per audit snapshot. */
  static final int AUDIT_SNAPSHOT_LIMIT = 32_768;

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
    return dispatch(actor, command, null);
  }

  /**
   * Times the whole command, outcome tagged, for the p95 SLOs in
   * SYSTEM-ARCHITECTURE section 6.7. A failure is tagged by its error code so a
   * spike of conflicts is distinguishable from a spike of denials.
   *
   * @param correlationId the id of the request that carried the command, recorded
   *     on its audit rows. Null for work with no request, such as a replayed offline
   *     batch, whose rows then take the logging context's id if it has one
   */
  public CommandResult dispatch(Actor actor, Command command, String correlationId) {
    long start = System.nanoTime();
    String outcome = "failed";
    try {
      CommandResult result = dispatchUntimed(actor, command, correlationId);
      outcome = result.replayed() ? "replayed" : "applied";
      return result;
    } catch (DomainException e) {
      outcome = e.code() == ErrorCode.FORBIDDEN ? "denied" : e.code().name().toLowerCase();
      throw e;
    } finally {
      metrics.record(
          "waypoint.command.duration",
          (System.nanoTime() - start) / 1_000_000,
          "kind",
          handlers.containsKey(command.kind()) ? command.kind() : "unknown",
          "outcome",
          outcome);
    }
  }

  private CommandResult dispatchUntimed(Actor actor, Command command, String correlationId) {
    CommandHandler handler = handlers.get(command.kind());
    if (handler == null) {
      // Deny by default: an unlisted command is refused and recorded, exactly as
      // a listed one the caller may not run. A 404 here would let anyone map the
      // kinds that exist by asking.
      metrics.increment("waypoint.command.denied", "kind", "unknown");
      audit.recordStandalone(
          AuditEntry.denied(
                  actor.userId(),
                  actor.deviceId(),
                  "platform:UnknownCommand",
                  null,
                  "no such command kind: " + abbreviate(command.kind()))
              .withCommand(command.commandId())
              .withCorrelation(correlationId));
      throw new DomainException(ErrorCode.FORBIDDEN, "This command is not permitted");
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
            AuditEntry.denied(actor.userId(), actor.deviceId(), handler.action(), resource, denial)
                .withCommand(command.commandId())
                .withTargetFromResource()
                .withCorrelation(correlationId));
      }
      throw new DomainException(ErrorCode.FORBIDDEN, denial);
    }

    String fingerprint = idempotency.fingerprint(command.kind(), command.payload());

    try {
      return execute(actor, command, handler, resource, fingerprint, correlationId);
    } catch (DomainException e) {
      // Scope is decided inside the transaction, because only there does row
      // level security see the actor. A denial raised there rolls back with
      // everything else, its audit row included, so it is recorded again here,
      // after the rollback, in a transaction of its own.
      if (e.code() == ErrorCode.FORBIDDEN) {
        metrics.increment("waypoint.command.denied", "kind", command.kind());
        audit.recordStandalone(
            AuditEntry.denied(
                    actor.userId(), actor.deviceId(), handler.action(), resource, e.getMessage())
                .withCommand(command.commandId())
                .withTargetFromResource()
                .withCorrelation(correlationId));
      } else if (RejectionReceipt.recordable(e.code()) && !(e instanceof RejectionReceipt.Stored)) {
        recordRejection(actor, command, handler, fingerprint, e);
      }
      throw e;
    }
  }

  /**
   * The transaction that raised the rejection has rolled back, receipt and all, so
   * the receipt is written in one of its own. {@code ON CONFLICT DO NOTHING}: two
   * retries of one rejected command may race, and either answer is the same.
   */
  private void recordRejection(
      Actor actor, Command command, CommandHandler handler, String fingerprint, DomainException e) {
    try {
      database.asModule(
          handler.moduleRole(),
          actor.userId(),
          () ->
              database.update(
                  """
                  INSERT INTO integration.command_receipts
                      (command_id, actor_id, command_kind, payload_hash, result_status, result_body)
                  VALUES (?, ?, ?, ?, ?, ?::jsonb)
                  ON CONFLICT (command_id, actor_id) DO NOTHING
                  """,
                  command.commandId(),
                  actor.userId(),
                  command.kind(),
                  fingerprint,
                  RejectionReceipt.status(e.code()),
                  RejectionReceipt.toJson(mapper, e)));
      metrics.increment("waypoint.command.rejection_recorded", "kind", command.kind());
    } catch (RuntimeException failure) {
      // The caller still gets the rejection. Without the receipt a retry simply
      // runs again, which is what happened before rejections had receipts.
      metrics.increment("waypoint.command.rejection_record_failed", "kind", command.kind());
    }
  }

  private CommandResult execute(
      Actor actor,
      Command command,
      CommandHandler handler,
      String resource,
      String fingerprint,
      String correlationId) {
    return database.asModule(
        handler.moduleRole(),
        actor.userId(),
        () -> {
          // SEC-03: asked again here, where the answer is consistent with what
          // the handler is about to read and write. Thrown, so everything rolls
          // back; dispatchUntimed records the denial once it has.
          authorizer
              .flatMap(a -> a.denyReasonInTransaction(actor, handler.action(), resource, command))
              .ifPresent(
                  reason -> {
                    throw new DomainException(ErrorCode.FORBIDDEN, reason);
                  });

          Map<String, Object> receipt =
              database.queryOne(
                  "SELECT payload_hash, result_status, result_body FROM integration.command_receipts"
                      + " WHERE command_id = ? AND actor_id = ?",
                  command.commandId(),
                  actor.userId());
          Optional<Object> replay =
              idempotency.replayOrReject(command.commandId(), fingerprint, receipt);
          if (replay.isPresent()) {
            metrics.increment("waypoint.command.replayed", "kind", command.kind());
            if (((Number) receipt.get("result_status")).intValue()
                >= RejectionReceipt.FIRST_REJECTION_STATUS) {
              // The same answer the first attempt got: the rule it broke, not a new run.
              throw RejectionReceipt.rebuild(mapper, replay.get());
            }
            return CommandResult.replayed(fromJson(replay.get()));
          }

          AuditContext auditContext = AuditContext.open(mapper);
          Object value;
          try {
            value =
                metrics.time(
                    "waypoint.command.handler.duration",
                    () -> handler.handle(actor, command),
                    "kind",
                    command.kind());
          } finally {
            auditContext.close();
          }

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
                      actor.userId(), actor.deviceId(), handler.action(), resource, "command applied")
                  .withCommand(command.commandId())
                  .withTargetFromResource()
                  .withCorrelation(correlationId)
                  .withState(
                      snapshot(auditContext.capturedBefore().orElse(null)),
                      snapshot(auditContext.capturedAfter().orElseGet(() -> valueToTree(value))))
                  .withPolicyGeneration(policyGeneration()));
          metrics.increment("waypoint.command.applied", "kind", command.kind());
          return CommandResult.executed(value);
        });
  }

  /** The generation the decision was taken under, read on this transaction's connection. */
  private Long policyGeneration() {
    return authorizer
        .map(CommandAuthorizer::policyGeneration)
        .filter(java.util.OptionalLong::isPresent)
        .map(java.util.OptionalLong::getAsLong)
        .orElse(null);
  }

  /**
   * A snapshot as it goes into the audit log: redacted, and bounded. The log is
   * append only and kept for years, so one command returning a whole plan must not
   * write megabytes into it for ever. A snapshot over the limit is replaced by a
   * note of its size; the full result is still in the receipt.
   */
  private String snapshot(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String json = AuditRedactor.redact(node).toString();
    if (json.length() <= AUDIT_SNAPSHOT_LIMIT) {
      return json;
    }
    return mapper.createObjectNode().put("truncated", true).put("chars", json.length()).toString();
  }

  private JsonNode valueToTree(Object value) {
    return value == null ? null : mapper.valueToTree(value);
  }

  /** A kind nobody recognised is the caller's text; an audit row keeps a bounded piece of it. */
  private static String abbreviate(String kind) {
    return kind.length() <= 80 ? kind : kind.substring(0, 80) + "...";
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
