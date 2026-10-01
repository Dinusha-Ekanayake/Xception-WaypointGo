package com.waypoint.dispatch.warehouse.application;

import com.waypoint.dispatch.platform.config.WarehouseProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.scheduling.ScheduledJob;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.UuidV7;
import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Rejected;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseDiscrepancyFound;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseOrderStatusChanged;
import com.waypoint.dispatch.warehouse.domain.CircuitBreaker;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Ambiguous;
import com.waypoint.dispatch.warehouse.domain.OrphanMatcher.Match;
import com.waypoint.dispatch.warehouse.domain.Placement;
import com.waypoint.dispatch.warehouse.domain.Placement.State;
import com.waypoint.dispatch.warehouse.domain.PlacementDecision;
import com.waypoint.dispatch.warehouse.domain.RetryPolicy;
import com.waypoint.dispatch.warehouse.domain.RetryPolicy.Decision;
import com.waypoint.dispatch.warehouse.domain.WarehouseOrder;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply;
import com.waypoint.dispatch.warehouse.domain.WarehouseReply.Answered;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcDiscrepancyRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcPlacementRepository;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient.Listing;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Settles placements saved while the warehouse was unreachable (D-G, STK-09).
 *
 * <p>A placement that was never sent is sent. One whose answer was lost is
 * looked for first, by content, because the warehouse keeps no reference of
 * ours ({@link OrphanMatcher}); only when nothing matches and the match window
 * has passed is it sent again. The answer reaches Ordering as
 * {@code warehouse.order_status_changed}, committed with the record of it.
 *
 * <p>The store is not waiting any more, so a partial answer cannot be put to
 * it: the reservation is released and reported as {@code insufficient}.
 */
@Component
public class StockUnknownRetryJob implements ScheduledJob {
  private static final int BATCH = 25;
  private static final int CANDIDATE_PAGES = 3;

  private final Database database;
  private final JdbcPlacementRepository placements;
  private final JdbcDiscrepancyRepository discrepancies;
  private final WarehouseHttpClient client;
  private final PlacementSender sender;
  private final EventPublisher events;
  private final WarehouseProperties properties;
  private final Metrics metrics;
  private final SecureRandom random = new SecureRandom();
  private final AtomicLong awaiting = new AtomicLong();

  public StockUnknownRetryJob(
      Database database,
      JdbcPlacementRepository placements,
      JdbcDiscrepancyRepository discrepancies,
      WarehouseHttpClient client,
      PlacementSender sender,
      EventPublisher events,
      WarehouseProperties properties,
      Metrics metrics) {
    this.database = database;
    this.placements = placements;
    this.discrepancies = discrepancies;
    this.client = client;
    this.sender = sender;
    this.events = events;
    this.properties = properties;
    this.metrics = metrics;
    metrics.gauge("waypoint.warehouse.stock_unknown", awaiting::get);
  }

  @Override
  public String name() {
    return "warehouse.stock-unknown-retry";
  }

  @Override
  public String cron() {
    return "0 * * * * *";
  }

  @Override
  public ModuleRole moduleRole() {
    return ModuleRole.WAREHOUSE;
  }

  /** How many placements still wait on the warehouse, as of the last run. */
  public long awaiting() {
    return awaiting.get();
  }

  @Override
  public void run(Instant now) {
    if (!client.configured()) {
      return;
    }
    List<Placement> due = database.asSystem(ModuleRole.WAREHOUSE, () -> placements.due(now, BATCH));
    for (Placement placement : due) {
      if (client.circuitState() == CircuitBreaker.State.OPEN) {
        break;
      }
      try {
        settle(placement, now);
      } catch (RuntimeException e) {
        metrics.increment("waypoint.warehouse.retry_failed");
        database.asSystem(ModuleRole.WAREHOUSE, () -> placements.recordError(placement.placementId(), e.toString(), now));
      }
    }
    awaiting.set(database.asSystem(ModuleRole.WAREHOUSE, placements::countAwaiting));
  }

  void settle(Placement placement, Instant now) {
    if (placement.state() == State.QUEUED && !placement.compensateOnly()) {
      send(placement, now);
      return;
    }
    if (placement.state() == State.QUEUED) {
      release(placement, State.RELEASED, now);
      return;
    }
    metrics.increment("waypoint.warehouse.timeout_then_query");
    Optional<Match> match = look(placement);
    if (match.isEmpty()) {
      return;
    }
    Decision decision = RetryPolicy.decide(placement, match.get(), now, properties.matchWindow());
    switch (decision.action()) {
      case ADOPT -> adopt(placement, decision.found().get(), now);
      case CANCEL_THEN_PLACE -> {
        sender.release(placement, decision.found().get().orderId(), now);
        send(placement, now);
      }
      case CANCEL_ONLY -> {
        sender.release(placement, decision.found().get().orderId(), now);
        release(placement, State.RELEASED, now);
      }
      case RAISE_AMBIGUOUS -> ambiguous(placement, (Ambiguous) match.get(), now);
      case WAIT -> metrics.increment("waypoint.warehouse.retry_waiting");
      case PLACE -> send(placement, now);
      case GIVE_UP -> release(placement, State.RELEASED, now);
    }
  }

  /** Candidates: the warehouse's newest reserved and pending orders in our warehouse, with items. */
  private Optional<Match> look(Placement placement) {
    List<WarehouseOrder> candidates = new ArrayList<>();
    for (String status : List.of("pending", "reserved")) {
      for (int page = 1; page <= CANDIDATE_PAGES; page++) {
        Listing<WarehouseOrder> listing = client.listOrders(placement.warehouseCode(), status, page, 50);
        if (listing.failure().isPresent()) {
          return Optional.empty();
        }
        boolean older = false;
        for (WarehouseOrder summary : listing.items()) {
          Instant earliest = placement.attempts().stream()
              .map(OrphanMatcher.Attempt::sentAt).min(Instant::compareTo).orElse(placement.createdAt())
              .minus(properties.matchWindow());
          if (summary.createdAt().isBefore(earliest)) {
            older = true;
            continue;
          }
          WarehouseReply detail = client.order(summary.orderId());
          if (!(detail instanceof Answered answered)) {
            return Optional.empty();
          }
          candidates.add(answered.order());
        }
        if (older || listing.items().size() < 50) {
          break;
        }
      }
    }
    var claimed = database.asSystem(ModuleRole.WAREHOUSE, () ->
        placements.claimed(candidates.stream().map(WarehouseOrder::orderId).toList()));
    return Optional.of(OrphanMatcher.match(
        placement.warehouseCode(), placement.attempts(), candidates, claimed, properties.matchWindow()));
  }

  private void send(Placement placement, Instant now) {
    PlacementResult result =
        sender.send(placement, (sent, outcome) -> announce(sent, outcome.result())).result();
    if (result instanceof PartiallyReserved partial) {
      sender.release(placement, partial.reservation().warehouseOrderRef(), now);
      database.asSystem(ModuleRole.WAREHOUSE, () ->
          placements.findByRef(placement.orderRef()).ifPresent(p ->
              placements.update(PlacementSender.copy(p, State.RELEASED, p.attempts(), p.warehouseOrderRef(),
                  Optional.of("cancelled"), Optional.empty(), Optional.empty(), 0, Optional.empty()), now)));
    }
  }

  /** The earlier attempt did reach the warehouse: take its answer as ours. */
  private void adopt(Placement placement, WarehouseOrder order, Instant now) {
    metrics.increment("waypoint.warehouse.orphan_adopted");
    Optional<String> unusable = PlacementDecision.unusable(order);
    if (unusable.isPresent() || "reserved".equals(order.status())) {
      // Unplannable, or a partial the store is no longer there to accept.
      sender.release(placement, order.orderId(), now);
      Placement released = PlacementSender.copy(placement, State.RELEASED, placement.attempts(),
          Optional.empty(), Optional.of("cancelled"), Optional.empty(), Optional.empty(), 0, Optional.empty());
      database.asSystem(ModuleRole.WAREHOUSE, () -> {
        long version = placements.update(released, now);
        announce(PlacementSender.withVersion(released, version), new Insufficient(List.of()));
      });
      return;
    }
    Reserved reserved = PlacementDecision.reserved(order);
    Placement placed = PlacementSender.copy(placement, State.PLACED, placement.attempts(),
        Optional.of(order.orderId()), Optional.of(order.status()), Optional.empty(), Optional.of(reserved), 0,
        Optional.empty());
    database.asSystem(ModuleRole.WAREHOUSE, () -> {
      long version = placements.update(placed, now);
      announce(PlacementSender.withVersion(placed, version), reserved);
    });
  }

  private void ambiguous(Placement placement, Ambiguous match, Instant now) {
    metrics.increment("waypoint.warehouse.orphan_ambiguous");
    Placement stuck = PlacementSender.copy(placement, State.AMBIGUOUS, placement.attempts(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), placement.retryCount(), Optional.empty());
    database.asSystem(ModuleRole.WAREHOUSE, () -> {
      placements.update(stuck, now);
      String detail = "placement " + placement.orderRef() + " matches several warehouse orders "
          + match.candidates() + "; neither placed nor adopted";
      if (discrepancies.raise(UuidV7.generate(now, random), placement.orderId(), "ambiguous:" + placement.orderRef(),
          "ambiguous_match", "STOCK_UNKNOWN", null, detail, now)) {
        events.publish(Actor.SYSTEM, new WarehouseDiscrepancyFound(
            placement.orderId(), placement.depotCode(), "STOCK_UNKNOWN", "ambiguous", detail));
      }
    });
  }

  private void release(Placement placement, State state, Instant now) {
    Placement done = PlacementSender.copy(placement, state, placement.attempts(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), placement.retryCount(), Optional.empty());
    database.asSystem(ModuleRole.WAREHOUSE, () -> placements.update(done, now));
  }

  /**
   * Tells Ordering how a late placement ended, in the transaction that records
   * it. A compensate-only placement is not announced: Waypoint no longer wants it.
   */
  private void announce(Placement placement, PlacementResult result) {
    if (placement.compensateOnly() || placement.orderRef().contains("#amend-")) {
      return;
    }
    if (result instanceof Reserved r) {
      events.publish(Actor.SYSTEM, new WarehouseOrderStatusChanged(
          placement.orderId(), Optional.of(r.warehouseOrderRef()), "pending", Optional.of(r)));
    } else if (result instanceof PartiallyReserved p) {
      // Released by send(); the store is no longer there to accept it.
      events.publish(Actor.SYSTEM, new WarehouseOrderStatusChanged(
          placement.orderId(), Optional.empty(), "insufficient", Optional.empty()));
    } else if (result instanceof Insufficient || result instanceof Rejected) {
      events.publish(Actor.SYSTEM, new WarehouseOrderStatusChanged(
          placement.orderId(), Optional.empty(), "insufficient", Optional.empty()));
    }
  }
}
