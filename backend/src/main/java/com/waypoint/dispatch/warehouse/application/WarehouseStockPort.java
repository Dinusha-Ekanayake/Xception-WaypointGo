package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.contract.StockPort;
import com.waypoint.dispatch.warehouse.domain.CircuitBreaker;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher;
import com.waypoint.dispatch.warehouse.domain.Placement;
import com.waypoint.dispatch.warehouse.domain.Placement.OnRequest;
import com.waypoint.dispatch.warehouse.domain.Placement.State;
import com.waypoint.dispatch.warehouse.domain.PlacementDecision;
import com.waypoint.dispatch.warehouse.domain.PlacementDecision.Outcome;
import com.waypoint.dispatch.warehouse.domain.WarehouseCode;
import com.waypoint.dispatch.warehouse.domain.WarehouseOrder;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Answered;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Refused;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcPlacementRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcStatusRequestRepository;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseKeyPresent;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * The warehouse behind {@link StockPort}, over its HTTP API.
 *
 * <p>Called synchronously from inside Ordering's command transaction. Its own
 * records are written in separate transactions ({@link Database#asSystemSeparately}),
 * because what was sent to the warehouse must be remembered even if Ordering's
 * transaction rolls back: the warehouse call cannot be rolled back with it.
 *
 * <p>Ordering retries a serializable conflict with the same order reference.
 * The second call finds the first one's record and answers from it, so a retry
 * never places twice (R-STK-11). If the first call's outcome is unknown it
 * answers {@link Unavailable} and leaves the order to the retry job, which looks
 * at the warehouse before sending again.
 */
@Component
@Conditional(WarehouseKeyPresent.class)
public class WarehouseStockPort implements StockPort {
  private final Database database;
  private final JdbcPlacementRepository placements;
  private final JdbcStatusRequestRepository statusRequests;
  private final WarehouseHttpClient client;
  private final PlacementSender sender;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public WarehouseStockPort(
      Database database,
      JdbcPlacementRepository placements,
      JdbcStatusRequestRepository statusRequests,
      WarehouseHttpClient client,
      PlacementSender sender,
      Metrics metrics,
      Clock clock) {
    this.database = database;
    this.placements = placements;
    this.statusRequests = statusRequests;
    this.client = client;
    this.sender = sender;
    this.metrics = metrics;
    this.clock = clock;
  }

  @Override
  public PlacementResult placeOrder(PlacementRequest request) {
    Optional<String> warehouse = WarehouseCode.forDepot(request.depotCode());
    if (warehouse.isEmpty()) {
      return new Rejected("depot " + request.depotCode() + " has no warehouse (A-25)");
    }
    Instant now = clock.now();
    Optional<Placement> existing =
        database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> placements.findByRef(request.orderRef()));

    OnRequest decision = Placement.onRequest(existing, request.lines());
    if (decision == OnRequest.REPLAY) {
      Placement p = existing.get();
      if (!p.orderId().equals(request.orderId())) {
        save(retarget(p, request, p.compensateOnly()));
      }
      metrics.increment("waypoint.warehouse.placement_replayed");
      return p.result().get();
    }
    if (decision == OnRequest.WAIT) {
      // The lines may have changed (a stock-unknown order amended): the retry job
      // places what is wanted now, after looking for what was sent before.
      save(retarget(existing.get(), request, false));
      metrics.increment("waypoint.warehouse.placement_deferred", "state", existing.get().state().code());
      return new Unavailable(
          "an earlier placement of " + request.orderRef()
              + " has an unknown outcome; the warehouse is checked before placing again");
    }

    Placement placement =
        existing
            .map(p -> retarget(p, request, false))
            .orElseGet(() -> fresh(request, request.orderRef(), warehouse.get(), now));
    if (client.circuitState() == CircuitBreaker.State.OPEN) {
      Placement queued =
          PlacementSender.copy(placement, State.QUEUED, placement.attempts(), Optional.empty(),
              Optional.empty(), Optional.empty(), Optional.empty(), placement.retryCount(), Optional.of(now));
      save(queued);
      metrics.increment("waypoint.warehouse.unavailable", "operation", "place", "reason", "circuit_open");
      return new Unavailable("warehouse circuit open");
    }
    return sender.send(placement, (p, o) -> {}).result();
  }

  @Override
  public PlacementResult amendOrder(String warehouseOrderRef, PlacementRequest request) {
    Optional<String> warehouse = WarehouseCode.forDepot(request.depotCode());
    if (warehouse.isEmpty()) {
      return new Rejected("depot " + request.depotCode() + " has no warehouse (A-25)");
    }
    Instant now = clock.now();
    Optional<Placement> main =
        database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> placements.findByRef(request.orderRef()));
    if (main.isPresent()
        && main.get().state() == State.PLACED
        && main.get().warehouseOrderRef().filter(ref -> !ref.equals(warehouseOrderRef)).isPresent()
        && OrphanMatcher.fingerprint(main.get().lines()).equals(OrphanMatcher.fingerprint(request.lines()))
        && main.get().result().isPresent()) {
      // A serializable retry of an amendment that already went through.
      return main.get().result().get();
    }
    int previous =
        database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> placements.forOrder(request.orderId()).size());
    Placement amendment = fresh(request, request.orderRef() + "#amend-" + previous, warehouse.get(), now);

    Outcome outcome =
        sender.send(amendment, (sent, o) -> {
          if (o.result() instanceof Reserved r && main.isPresent()) {
            merge(sent, main.get(), r, request, warehouseOrderRef);
          } else if (o.outcomeUnknown()) {
            // Ordering refuses the amendment, so anything this attempt created
            // is unwanted: the retry job cancels it if it finds it.
            placements.update(compensateOnly(sent), clock.now());
          }
        });

    PlacementResult result = outcome.result();
    if (result instanceof PartiallyReserved partial) {
      // Amendment is strict: the store re-submits rather than half-amends.
      sender.release(amendment, partial.reservation().warehouseOrderRef(), now);
      database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> {
        placements.findByRef(amendment.orderRef()).ifPresent(p ->
            placements.update(
                PlacementSender.copy(p, State.RELEASED, p.attempts(), Optional.empty(),
                    Optional.of("cancelled"), Optional.empty(), Optional.empty(), p.retryCount(),
                    Optional.empty()),
                clock.now()));
        return null;
      });
      return new Insufficient(partial.lines());
    }
    return result;
  }

  @Override
  public ConfirmResult confirmReservation(String warehouseOrderRef) {
    WarehouseReply reply = client.confirm(warehouseOrderRef);
    if (reply instanceof Refused refused && refused.status() == 409) {
      // Already confirmed by an earlier try of the same command, or expired.
      reply = client.order(warehouseOrderRef);
    }
    if (reply instanceof Answered answered) {
      WarehouseOrder order = answered.order();
      if (!"pending".equals(order.status())) {
        recordStatus(warehouseOrderRef, order.status(), State.RELEASED);
        metrics.increment("waypoint.warehouse.confirm", "result", "expired");
        return new Expired("the reservation is " + order.status());
      }
      Reserved reserved = PlacementDecision.reserved(order);
      List<StockLine> lines =
          order.items().stream()
              .filter(i -> i.quantity() > 0)
              .map(i -> new StockLine(i.productId(), i.quantity()))
              .toList();
      database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> {
        placements.findByWarehouseRef(warehouseOrderRef).ifPresent(p ->
            placements.update(
                new Placement(p.placementId(), p.orderRef(), p.orderId(), p.depotCode(), p.warehouseCode(),
                    lines, p.attempts(), State.PLACED, p.compensateOnly(), p.warehouseOrderRef(),
                    Optional.of("pending"), Optional.empty(), Optional.of(reserved), p.retryCount(),
                    Optional.empty(), p.createdAt(), p.rowVersion()),
                clock.now()));
        return null;
      });
      metrics.increment("waypoint.warehouse.confirm", "result", "confirmed");
      return new Confirmed(reserved, lines);
    }
    if (reply instanceof Refused refused) {
      metrics.increment("waypoint.warehouse.confirm", "result", "refused");
      return new Expired("warehouse refused: " + refused.code() + ": " + refused.message());
    }
    metrics.increment("waypoint.warehouse.confirm", "result", "unavailable");
    return new Unavailable(((WarehouseReply.Failed) reply).reason());
  }

  // ---- internals ------------------------------------------------------------

  /** The amendment's new warehouse order becomes the order's; the old one is cancelled. */
  private void merge(
      Placement amendment, Placement main, Reserved reserved, PlacementRequest request, String oldRef) {
    Instant now = clock.now();
    placements.update(
        PlacementSender.copy(amendment, State.MERGED, amendment.attempts(), Optional.empty(),
            Optional.of("pending"), Optional.empty(), Optional.of(reserved), 0, Optional.empty()),
        now);
    Placement current = placements.findByRef(main.orderRef()).orElse(main);
    placements.update(
        new Placement(current.placementId(), current.orderRef(), request.orderId(), current.depotCode(),
            current.warehouseCode(), request.lines(), current.attempts(), State.PLACED, false,
            Optional.of(reserved.warehouseOrderRef()), Optional.of("pending"), Optional.empty(),
            Optional.of(reserved), 0, Optional.empty(), current.createdAt(), current.rowVersion()),
        now);
    statusRequests.request(
        UuidV7.generate(now, random), request.orderId(), oldRef, "cancelled", "amended", now);
  }

  private Placement fresh(PlacementRequest request, String orderRef, String warehouse, Instant now) {
    return new Placement(
        UuidV7.generate(now, random), orderRef, request.orderId(), request.depotCode(), warehouse,
        request.lines(), List.of(), State.QUEUED, false, Optional.empty(), Optional.empty(),
        Optional.empty(), Optional.empty(), 0, Optional.empty(), now, 0);
  }

  private static Placement retarget(Placement p, PlacementRequest request, boolean compensateOnly) {
    return new Placement(
        p.placementId(), p.orderRef(), request.orderId(), p.depotCode(), p.warehouseCode(),
        request.lines(), p.attempts(), p.state(), compensateOnly, p.warehouseOrderRef(),
        p.warehouseStatus(), p.expiresAt(), p.result(), p.retryCount(), p.nextAttemptAt(),
        p.createdAt(), p.rowVersion());
  }

  private static Placement compensateOnly(Placement p) {
    return new Placement(
        p.placementId(), p.orderRef(), p.orderId(), p.depotCode(), p.warehouseCode(), p.lines(),
        p.attempts(), p.state(), true, p.warehouseOrderRef(), p.warehouseStatus(), p.expiresAt(),
        p.result(), p.retryCount(), p.nextAttemptAt(), p.createdAt(), p.rowVersion());
  }

  private void save(Placement p) {
    database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> {
      if (p.rowVersion() == 0) {
        placements.insert(p, clock.now());
      } else {
        placements.update(p, clock.now());
      }
      return null;
    });
  }

  private void recordStatus(String warehouseOrderRef, String status, State state) {
    database.asSystemSeparately(ModuleRole.WAREHOUSE, () -> {
      placements.findByWarehouseRef(warehouseOrderRef).ifPresent(p ->
          placements.update(
              PlacementSender.copy(p, state, p.attempts(), p.warehouseOrderRef(), Optional.of(status),
                  Optional.empty(), p.result(), p.retryCount(), Optional.empty()),
              clock.now()));
      return null;
    });
  }
}
