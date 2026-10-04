package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.Stamp;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The events Execution consumes. The relay runs each one in a transaction opened
 * as waypoint_execution for the system actor, which row-level security lets see
 * every vehicle: an event about a trip is not scoped to whoever caused it.
 *
 * <p>Names are stable. Renaming one replays its history.
 */
public final class ExecutionConsumers {
  private ExecutionConsumers() {}

  abstract static class ExecutionConsumer<E extends DomainEvent> implements EventSubscriber<E> {
    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.EXECUTION;
    }
  }

  /** The trip left the dock: its stops become the driver's run sheet. */
  @Component
  public static class OnTripReleased extends ExecutionConsumer<TripReleased> {
    private final RunSheetBuilder builder;

    public OnTripReleased(RunSheetBuilder builder) {
      this.builder = builder;
    }

    @Override
    public String consumerName() {
      return "execution.on-trip-released";
    }

    @Override
    public Class<TripReleased> eventType() {
      return TripReleased.class;
    }

    @Override
    public void on(EventEnvelope<TripReleased> envelope) {
      builder.build(envelope.payload());
    }
  }

  /**
   * A published plan was revised while trips are on the road, which is what a
   * dispatcher does after a breakdown (EXE-08). A stop nobody has reached that
   * is no longer on its trip is skipped; if it was moved to another trip, that
   * trip's release builds it again. A stop already reached is left alone: what
   * happened there happened.
   *
   * <p>Skipping moves the stop's version on, so a command the driver queued
   * against it offline is held as a conflict instead of applied (EXE-03).
   */
  @Component
  public static class OnPlanRevised extends ExecutionConsumer<PlanRevised> {
    private final JdbcDeliveryRepository deliveries;
    private final Metrics metrics;
    private final Clock clock;

    public OnPlanRevised(JdbcDeliveryRepository deliveries, Metrics metrics, Clock clock) {
      this.deliveries = deliveries;
      this.metrics = metrics;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "execution.on-plan-revised";
    }

    @Override
    public Class<PlanRevised> eventType() {
      return PlanRevised.class;
    }

    @Override
    public void on(EventEnvelope<PlanRevised> envelope) {
      PlanRevised e = envelope.payload();
      Map<UUID, Set<UUID>> ordersByTrip = new HashMap<>();
      for (PlannedTrip trip : e.trips()) {
        Set<UUID> orders = ordersByTrip.computeIfAbsent(trip.tripId(), id -> new HashSet<>());
        for (PlannedStop stop : trip.stops()) {
          orders.add(stop.orderId());
        }
      }
      Instant now = clock.now();
      int skipped = 0;
      for (DeliveryRecord pending : deliveries.pendingOfDay(e.depotCode(), e.serviceDate())) {
        if (ordersByTrip.getOrDefault(pending.tripId(), Set.of()).contains(pending.orderId())) {
          continue;
        }
        deliveries.save(
            pending.skip(now), pending.rowVersion(), new Stamp(Actor.SYSTEM_ID, null, now, null));
        skipped++;
      }
      if (skipped > 0) {
        metrics.count("waypoint.execution.skipped_by_replan", skipped, "depot", e.depotCode());
      }
    }
  }
}
