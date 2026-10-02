package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseDiscrepancyFound;
import com.waypoint.dispatch.warehouse.domain.CircuitBreaker;
import com.waypoint.dispatch.warehouse.domain.RetryPolicy;
import com.waypoint.dispatch.warehouse.domain.WarehouseLifecycle;
import com.waypoint.dispatch.warehouse.domain.WarehouseLifecycle.Step;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Answered;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Refused;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcDiscrepancyRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcPlacementRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcStatusRequestRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcStatusRequestRepository.StatusRequest;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Makes the cancel, ship and deliver calls the consumers queued.
 *
 * <p>A call that fails is retried with backoff until the warehouse answers. A
 * {@code 409} is not retried: the job reads the warehouse's status, treats a
 * target already reached as done (a redelivered event, or a cancel that raced a
 * webhook), and records anything else as a divergence to raise, never a loop
 * (STK-10, R-STK-10).
 */
@Component
public class StatusRequestJob implements ScheduledJob {
  private static final int BATCH = 50;

  private final Database database;
  private final JdbcStatusRequestRepository requests;
  private final JdbcPlacementRepository placements;
  private final JdbcDiscrepancyRepository discrepancies;
  private final WarehouseHttpClient client;
  private final EventPublisher events;
  private final Metrics metrics;
  private final SecureRandom random = new SecureRandom();

  public StatusRequestJob(
      Database database,
      JdbcStatusRequestRepository requests,
      JdbcPlacementRepository placements,
      JdbcDiscrepancyRepository discrepancies,
      WarehouseHttpClient client,
      EventPublisher events,
      Metrics metrics) {
    this.database = database;
    this.requests = requests;
    this.placements = placements;
    this.discrepancies = discrepancies;
    this.client = client;
    this.events = events;
    this.metrics = metrics;
  }

  @Override
  public String name() {
    return "warehouse.status-requests";
  }

  @Override
  public String cron() {
    return "30 * * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.WAREHOUSE;
  }

  @Override
  public void run(Instant now) {
    if (!client.configured()) {
      return;
    }
    List<StatusRequest> due = database.asSystem(ModuleRole.WAREHOUSE, () -> requests.due(now, BATCH));
    for (StatusRequest request : due) {
      if (client.circuitState() == CircuitBreaker.State.OPEN) {
        break;
      }
      apply(request, now);
    }
  }

  void apply(StatusRequest request, Instant now) {
    WarehouseReply reply = client.setStatus(request.warehouseOrderRef(), request.targetStatus());
    if (reply instanceof Answered answered) {
      done(request, answered.order().status(), now);
      return;
    }
    if (reply instanceof Refused refused && refused.status() == 409) {
      WarehouseReply current = client.order(request.warehouseOrderRef());
      if (current instanceof Answered answered) {
        String status = answered.order().status();
        if (WarehouseLifecycle.step(status, request.targetStatus()) == Step.DONE) {
          done(request, status, now);
        } else {
          diverged(request, status, refused.message(), now);
        }
        return;
      }
      reply = current;
    }
    if (reply instanceof Refused refused && refused.status() == 404) {
      diverged(request, "absent", refused.message(), now);
      return;
    }
    String error = reply instanceof WarehouseReply.Failed f ? f.reason() : String.valueOf(reply);
    metrics.increment("waypoint.warehouse.status_call", "target", request.targetStatus(), "result", "retry");
    database.asSystem(ModuleRole.WAREHOUSE, () ->
        requests.retryAt(request, RetryPolicy.nextAttempt(now, request.attempts()), error, now));
  }

  private void done(StatusRequest request, String warehouseStatus, Instant now) {
    metrics.increment("waypoint.warehouse.status_call", "target", request.targetStatus(), "result", "done");
    database.asSystem(ModuleRole.WAREHOUSE, () -> {
      requests.finish(request, "done", null, now);
      placements.findByWarehouseRef(request.warehouseOrderRef()).ifPresent(p ->
          placements.update(
              PlacementSender.copy(p, p.state(), p.attempts(), p.warehouseOrderRef(),
                  Optional.of(warehouseStatus), p.expiresAt(), p.result(), p.retryCount(), p.nextAttemptAt()),
              now));
    });
  }

  private void diverged(StatusRequest request, String warehouseStatus, String message, Instant now) {
    metrics.increment("waypoint.warehouse.status_call", "target", request.targetStatus(), "result", "diverged");
    String detail = "asked the warehouse to mark " + request.warehouseOrderRef() + " "
        + request.targetStatus() + " (" + request.cause() + "); it is " + warehouseStatus + ": " + message;
    database.asSystem(ModuleRole.WAREHOUSE, () -> {
      requests.finish(request, "diverged", detail, now);
      String depot = placements.findByWarehouseRef(request.warehouseOrderRef()).map(p -> p.depotCode()).orElse(null);
      if (discrepancies.raise(UuidV7.generate(now, random), request.orderId(), request.warehouseOrderRef(),
          "invalid_transition", null, warehouseStatus, detail, now)) {
        events.publish(Actor.SYSTEM, new WarehouseDiscrepancyFound(
            request.orderId(), depot, request.targetStatus(), warehouseStatus, detail));
      }
    });
  }
}
