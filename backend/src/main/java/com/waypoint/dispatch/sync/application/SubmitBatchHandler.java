package com.waypoint.dispatch.sync.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.identity.contract.OperatorQuery;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandAuthorizer;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.platform.messaging.CommandResult;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.sync.contract.SyncCommands;
import com.waypoint.dispatch.sync.contract.SyncCommands.SubmitBatch;
import com.waypoint.dispatch.sync.contract.SyncCommands.SubmittedOperation;
import com.waypoint.dispatch.sync.contract.SyncViews.OperationStatus;
import com.waypoint.dispatch.sync.contract.SyncViews.OperationView;
import com.waypoint.dispatch.sync.domain.OperationOutcome;
import com.waypoint.dispatch.sync.infrastructure.OperationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code POST /api/sync}: apply a device's queued operations, in the order it recorded them.
 *
 * <p>Deliberately not one transaction. Each operation is recorded, then dispatched through the
 * command bus in its own transaction exactly as an online write would be (rule 3, and no second
 * write path), then its outcome is recorded. A crash between those steps leaves the operation
 * {@code RECEIVED}; the device sends it again and the bus answers from its receipt, so it still
 * applies at most once.
 *
 * <p>A conflict or a rejection settles that operation and the batch carries on, because the next
 * operation may be about a different record. Anything the server cannot decide right now stops the
 * batch, so nothing overtakes it.
 */
@Component
public class SubmitBatchHandler {
  public static final int MAX_BATCH = 100;

  /** Concurrent batches this instance will take before answering 429. */
  private static final int CONCURRENT_BATCHES = 8;

  private static final Logger log = LoggerFactory.getLogger(SubmitBatchHandler.class);

  private final Semaphore capacity = new Semaphore(CONCURRENT_BATCHES);
  private final CommandBus bus;
  private final Optional<CommandAuthorizer> authorizer;
  private final OperationRepository operations;
  private final Database database;
  private final Metrics metrics;
  private final ObjectMapper mapper;
  private final Optional<OperatorQuery> operators;

  public SubmitBatchHandler(
      CommandBus bus,
      Optional<CommandAuthorizer> authorizer,
      OperationRepository operations,
      Database database,
      Metrics metrics,
      ObjectMapper mapper,
      Optional<OperatorQuery> operators) {
    this.bus = bus;
    this.authorizer = authorizer;
    this.operations = operations;
    this.database = database;
    this.metrics = metrics;
    this.mapper = mapper;
    this.operators = operators;
  }

  /** Thrown when the instance is at capacity; the web layer turns it into 429 with Retry-After. */
  public static final class Busy extends RuntimeException {
    public Busy() {
      super("Sync is at capacity");
    }
  }

  /**
   * One line per operation the server reached. An operation missing from the answer was not
   * reached and stays on the device.
   *
   * @param status {@code RECEIVED} means recorded but not decided; send it again later
   * @param rowVersion the operation's version after this answer, which a device names when it
   *     discards or resolves a held write; null when the id belongs to someone else
   */
  public record Outcome(
      UUID operationId,
      long sequence,
      OperationStatus status,
      String problemCode,
      String detail,
      boolean replayed,
      Long rowVersion) {}

  public List<Outcome> submit(Actor actor, SubmitBatch batch) {
    return submit(actor, null, batch);
  }

  public List<Outcome> submit(Actor actor, String sessionToken, SubmitBatch batch) {
    if (!capacity.tryAcquire()) {
      metrics.increment("waypoint.sync.busy");
      throw new Busy();
    }
    try {
      return apply(actor, sessionToken, batch);
    } finally {
      capacity.release();
    }
  }

  private List<Outcome> apply(Actor actor, String sessionToken, SubmitBatch batch) {
    if (batch.deviceId() == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "deviceId is required");
    }
    if (batch.operations().size() > MAX_BATCH) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Send at most " + MAX_BATCH + " operations per batch");
    }
    String denial =
        authorizer.isPresent()
            ? authorizer.get().denyReason(actor, SyncCommands.SUBMIT, null, null).orElse(null)
            : "Authorization is not configured";
    if (denial != null) {
      throw new DomainException(ErrorCode.FORBIDDEN, denial);
    }

    List<SubmittedOperation> ordered =
        batch.operations().stream()
            .sorted(Comparator.comparingLong(SubmittedOperation::sequence))
            .toList();
    List<Outcome> outcomes = new ArrayList<>();
    boolean stopped = false;
    for (SubmittedOperation op : ordered) {
      Outcome outcome = applyOne(actor, sessionToken, batch.deviceId(), op);
      outcomes.add(outcome);
      metrics.increment("waypoint.sync.operation", "status", outcome.status().name());
      if (outcome.status() == OperationStatus.RECEIVED) {
        stopped = true;
        break;
      }
    }
    if (!stopped) {
      recordTimeToDrain(actor, batch.deviceId(), ordered);
    }
    return outcomes;
  }

  /**
   * Time to drain (EXE-02): from the oldest write in this batch being recorded on the device to the
   * moment the server holds nothing undecided from that device. A batch that stopped on an outage
   * has not drained, and neither has a device with operations still in flight.
   */
  private void recordTimeToDrain(Actor actor, UUID deviceId, List<SubmittedOperation> ordered) {
    Optional<Instant> oldest =
        ordered.stream()
            .map(SubmittedOperation::command)
            .map(c -> c == null ? null : c.clientRecordedAt())
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder());
    if (oldest.isEmpty()) {
      return;
    }
    boolean drained =
        database.asModule(
            ModuleRole.SYNC, actor.userId(), () -> operations.pendingFor(deviceId).isEmpty());
    if (drained) {
      metrics.record(
          "waypoint.sync.time_to_drain",
          Math.max(0, Duration.between(oldest.get(), Instant.now()).toMillis()));
    }
  }

  private Outcome applyOne(Actor actor, String sessionToken, UUID deviceId, SubmittedOperation op) {
    Command command = op.command();
    if (command == null || command.commandId() == null || command.kind() == null) {
      throw new DomainException(
          ErrorCode.VALIDATION_FAILED, "Operation " + op.sequence() + " has no command envelope");
    }
    UUID id = command.commandId();

    Optional<OperationView> known = recordOrFind(actor, deviceId, op);
    if (known.isEmpty()) {
      return new Outcome(
          id, op.sequence(), OperationStatus.REJECTED, ErrorCode.CONFLICT.name(),
          "This operation id is already in use", false, null);
    }
    if (OperationOutcome.isSettled(known.get().status())) {
      // A replayed batch: answer what the device was told the first time.
      return new Outcome(
          id, op.sequence(), known.get().status(), known.get().problemCode().orElse(null), null,
          true, version(actor, id));
    }

    if (command.clientRecordedAt() != null) {
      // Queue age (EXE-01): how long this write waited on the device.
      metrics.record(
          "waypoint.sync.queue_age",
          Math.max(0, Duration.between(command.clientRecordedAt(), Instant.now()).toMillis()),
          "kind", command.kind());
    }

    try {
      Actor commandActor = actor;
      // Loading work must name its loader; any other command may (#136).
      if (command.kind().startsWith("loading:") || command.actingUserId() != null) {
        if (command.actingUserId() == null || command.clientRecordedAt() == null || operators.isEmpty()) {
          throw new DomainException(ErrorCode.FORBIDDEN,
              "Queued loading work must identify the loader who recorded it on this device");
        }
        commandActor = operators.get().operatorAt(sessionToken, command.actingUserId(), command.clientRecordedAt())
            .orElseThrow(() -> new DomainException(ErrorCode.FORBIDDEN,
                "This loader was not operating the device when the work was recorded"));
      }
      CommandResult result = bus.dispatch(commandActor, command);
      long version = settle(actor, id, OperationStatus.APPLIED, null, null);
      return new Outcome(
          id, op.sequence(), OperationStatus.APPLIED, null, null, result.replayed(), version);
    } catch (DomainException e) {
      Optional<OperationStatus> settled = OperationOutcome.forFailure(e.code());
      if (settled.isEmpty()) {
        return new Outcome(
            id, op.sequence(), OperationStatus.RECEIVED, e.code().name(), e.getMessage(), false,
            version(actor, id));
      }
      long version = settle(actor, id, settled.get(), e.code().name(), e.getMessage());
      return new Outcome(
          id, op.sequence(), settled.get(), e.code().name(), e.getMessage(), false, version);
    } catch (RuntimeException e) {
      // Not the operation's fault as far as anyone can tell. Keep it in flight.
      log.error("Sync could not apply operation {} ({})", id, command.kind(), e);
      return new Outcome(
          id, op.sequence(), OperationStatus.RECEIVED, "INTERNAL_ERROR",
          "The server could not apply this yet", false, version(actor, id));
    }
  }

  private Optional<OperationView> recordOrFind(Actor actor, UUID deviceId, SubmittedOperation op) {
    Command command = op.command();
    String json;
    try {
      json = mapper.writeValueAsString(command);
    } catch (Exception e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "The command is not serialisable");
    }
    return database.asModule(
        ModuleRole.SYNC,
        actor.userId(),
        () -> {
          boolean inserted = operations.insert(
              command.commandId(),
              actor.userId(),
              deviceId,
              op.sequence(),
              command.kind(),
              json,
              command.expectedVersion(),
              command.clientRecordedAt());
          if (!inserted && !operations.matches(command.commandId(), actor.userId(), deviceId, json)) {
            return Optional.empty();
          }
          return operations.find(command.commandId());
        });
  }

  /** Settles the operation and answers its version afterwards, in one transaction. */
  private long settle(Actor actor, UUID id, OperationStatus status, String code, String detail) {
    return database.asModule(
        ModuleRole.SYNC,
        actor.userId(),
        () -> {
          operations.settle(id, status, code, detail);
          return operations.rowVersion(id).orElse(0L);
        });
  }

  private Long version(Actor actor, UUID id) {
    return database.asModule(
        ModuleRole.SYNC, actor.userId(), () -> operations.rowVersion(id).orElse(null));
  }
}
