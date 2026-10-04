package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Rejected;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Attempt;
import com.waypoint.dispatch.warehouse.domain.PlacementDecision;
import com.waypoint.dispatch.warehouse.domain.PlacementDecision.Outcome;
import com.waypoint.dispatch.warehouse.domain.Placement;
import com.waypoint.dispatch.warehouse.domain.Placement.State;
import com.waypoint.dispatch.warehouse.domain.RetryPolicy;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Answered;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcPlacementRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcStatusRequestRepository;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.springframework.stereotype.Component;

/**
 * Sends one {@code POST /orders} the only safe way: record the attempt and
 * commit it, then call, then record the answer (R-STK-11).
 *
 * <p>The attempt is committed in a transaction of its own before the call, so a
 * timeout, a crash, or the caller's transaction rolling back still leaves the
 * record the retry job needs to look before sending again. The HTTP call holds
 * no transaction of ours.
 */
@Component
class PlacementSender {
  private final Database database;
  private final JdbcPlacementRepository placements;
  private final JdbcStatusRequestRepository statusRequests;
  private final WarehouseHttpClient client;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  PlacementSender(
      Database database,
      JdbcPlacementRepository placements,
      JdbcStatusRequestRepository statusRequests,
      WarehouseHttpClient client,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.placements = placements;
    this.statusRequests = statusRequests;
    this.client = client;
    this.metrics = metrics;
    this.clock = clock;
  }

  /**
   * @param placement the record to send, already holding the wanted lines; new
   *     when {@code rowVersion} is 0
   * @param inTransaction runs inside the transaction that records the answer,
   *     for an event that must commit with it
   */
  Outcome send(Placement placement, BiConsumer<Placement, Outcome> inTransaction) {
    Instant now = clock.now();
    Placement attempting = with(placement, State.ATTEMPTING, placement.withAttempt(now));
    Placement recorded =
        database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> {
          if (placement.rowVersion() == 0) {
            placements.insert(attempting, now);
            return withVersion(attempting, 1);
          }
          return withVersion(attempting, placements.update(attempting, now));
        });

    WarehouseReply reply = client.placeOrder(placement.warehouseCode(), placement.lines());
    Outcome outcome = PlacementDecision.decide(reply, placement.lines());
    outcome.release().ifPresent(ref -> release(placement, ref, now));

    Placement answered = answered(recorded, outcome, now);
    database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> {
      long version = placements.update(answered, clock.now());
      inTransaction.accept(withVersion(answered, version), outcome);
      return null;
    });
    metrics.increment("waypoint.warehouse.placement", "result", kind(outcome.result()));
    if (outcome.outcomeUnknown()) {
      metrics.increment("waypoint.warehouse.placement_outcome_unknown");
    }
    return outcome;
  }

  /** Cancels a warehouse order Waypoint must not keep; a failed cancel is retried as a request. */
  void release(Placement placement, String warehouseOrderRef, Instant now) {
    WarehouseReply reply = client.setStatus(warehouseOrderRef, "cancelled");
    if (!(reply instanceof Answered)) {
      database.asSystemSeparately(ModuleRole.WAREHOUSE, () ->
          statusRequests.request(
              UuidV7.generate(now, random), placement.orderId(), warehouseOrderRef, "cancelled",
              "release", now));
      metrics.increment("waypoint.warehouse.release_deferred");
    }
  }

  private Placement answered(Placement p, Outcome outcome, Instant now) {
    PlacementResult result = outcome.result();
    List<Attempt> attempts = p.attempts();
    if (result instanceof Reserved r) {
      return copy(p, State.PLACED, attempts, Optional.of(r.warehouseOrderRef()), Optional.of("pending"),
          Optional.empty(), Optional.of(result), 0, Optional.empty());
    }
    if (result instanceof PartiallyReserved partial) {
      return copy(p, State.PARTIAL, attempts, Optional.of(partial.reservation().warehouseOrderRef()),
          Optional.of("reserved"), Optional.of(partial.expiresAt()), Optional.of(result), 0, Optional.empty());
    }
    if (result instanceof Insufficient || result instanceof Rejected) {
      return copy(p, State.REJECTED, attempts, Optional.empty(), Optional.empty(), Optional.empty(),
          Optional.empty(), 0, Optional.empty());
    }
    // Unavailable. Never sent: drop the attempt we recorded and queue it. Sent:
    // the outcome is unknown, so the retry job must look before sending again.
    boolean sent = outcome.outcomeUnknown();
    List<Attempt> kept =
        sent ? attempts : attempts.subList(0, attempts.size() - 1);
    int failures = p.retryCount() + 1;
    return copy(p, sent ? State.UNKNOWN : State.QUEUED, kept, Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), failures, Optional.of(RetryPolicy.nextAttempt(now, failures - 1)));
  }

  static Placement with(Placement p, State state, List<Attempt> attempts) {
    return copy(p, state, attempts, p.warehouseOrderRef(), p.warehouseStatus(), p.expiresAt(), p.result(),
        p.retryCount(), p.nextAttemptAt());
  }

  static Placement copy(
      Placement p, State state, List<Attempt> attempts,
      Optional<String> ref, Optional<String> warehouseStatus, Optional<Instant> expiresAt,
      Optional<PlacementResult> result, int retryCount, Optional<Instant> nextAttemptAt) {
    return new Placement(
        p.placementId(), p.orderRef(), p.orderId(), p.depotCode(), p.warehouseCode(), p.lines(), attempts,
        state, p.compensateOnly(), ref, warehouseStatus, expiresAt, result, retryCount, nextAttemptAt,
        p.createdAt(), p.rowVersion());
  }

  static Placement withVersion(Placement p, long version) {
    return new Placement(
        p.placementId(), p.orderRef(), p.orderId(), p.depotCode(), p.warehouseCode(), p.lines(),
        p.attempts(), p.state(), p.compensateOnly(), p.warehouseOrderRef(), p.warehouseStatus(),
        p.expiresAt(), p.result(), p.retryCount(), p.nextAttemptAt(), p.createdAt(), version);
  }

  static String kind(PlacementResult result) {
    if (result instanceof Reserved) {
      return "reserved";
    }
    if (result instanceof PartiallyReserved) {
      return "partial";
    }
    if (result instanceof Insufficient) {
      return "insufficient";
    }
    if (result instanceof Rejected) {
      return "rejected";
    }
    return "unavailable";
  }
}
