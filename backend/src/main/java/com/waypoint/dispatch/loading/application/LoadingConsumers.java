package com.waypoint.dispatch.loading.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.VehicleAtDepot;
import com.waypoint.dispatch.issues.contract.IssueEvents.ShortfallResolved;
import com.waypoint.dispatch.loading.infrastructure.JdbcManifestWriter;
import com.waypoint.dispatch.loading.infrastructure.JdbcVehicleArrivals;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import org.springframework.stereotype.Component;

/**
 * The events Loading consumes. The relay runs each one in a transaction opened
 * as waypoint_loading for the system actor, which row-level security lets see
 * every depot: an event about a trip is not scoped to whoever caused it.
 *
 * <p>Names are stable. Renaming one replays its history.
 */
public final class LoadingConsumers {
  private LoadingConsumers() {}

  abstract static class LoadingConsumer<E extends DomainEvent> implements EventSubscriber<E> {
    @Override
    public final ModuleRole moduleRole() {
      return ModuleRole.LOADING;
    }
  }

  /** A plan was published: build each trip's manifest (R-LOD-01, D-L). */
  @Component
  public static class OnPlanPublished extends LoadingConsumer<PlanPublished> {
    private final ManifestBuilder builder;

    public OnPlanPublished(ManifestBuilder builder) {
      this.builder = builder;
    }

    @Override
    public String consumerName() {
      return "loading.on-plan-published";
    }

    @Override
    public Class<PlanPublished> eventType() {
      return PlanPublished.class;
    }

    @Override
    public void on(EventEnvelope<PlanPublished> envelope) {
      PlanPublished e = envelope.payload();
      builder.build(e.planId(), e.supersedes(), e.depotCode(), e.serviceDate(), e.planVersion(), e.trips());
    }
  }

  /**
   * A published plan was revised: the new version replaces the old copy. Ticks
   * on unchanged item lines carry over; changed lines must be checked again, so
   * a stale list never counts toward release (R-LOD-03, R-LOD-07).
   */
  @Component
  public static class OnPlanRevised extends LoadingConsumer<PlanRevised> {
    private final ManifestBuilder builder;

    public OnPlanRevised(ManifestBuilder builder) {
      this.builder = builder;
    }

    @Override
    public String consumerName() {
      return "loading.on-plan-revised";
    }

    @Override
    public Class<PlanRevised> eventType() {
      return PlanRevised.class;
    }

    @Override
    public void on(EventEnvelope<PlanRevised> envelope) {
      PlanRevised e = envelope.payload();
      builder.build(e.planId(), java.util.Optional.of(e.supersedes()), e.depotCode(), e.serviceDate(), e.planVersion(), e.trips());
    }
  }

  /** Issues recorded what was done about a flagged item. The flag stays on record, now resolved. */
  @Component
  public static class OnShortfallResolved extends LoadingConsumer<ShortfallResolved> {
    private final JdbcManifestWriter writer;
    private final Clock clock;

    public OnShortfallResolved(JdbcManifestWriter writer, Clock clock) {
      this.writer = writer;
      this.clock = clock;
    }

    @Override
    public String consumerName() {
      return "loading.on-shortfall-resolved";
    }

    @Override
    public Class<ShortfallResolved> eventType() {
      return ShortfallResolved.class;
    }

    @Override
    public void on(EventEnvelope<ShortfallResolved> envelope) {
      ShortfallResolved e = envelope.payload();
      writer.resolveShortfalls(e.tripId(), e.orderId(), e.resolution(), clock.now());
    }
  }

  /**
   * R-LOD-12: the driver is at the depot with the vehicle. The dock board marks
   * the vehicle's trips "Driver at the dock" and puts them first, so the loader
   * takes the trip whose vehicle is waiting. The first report of the day is kept.
   */
  @Component
  public static class OnVehicleAtDepot extends LoadingConsumer<VehicleAtDepot> {
    private final JdbcVehicleArrivals arrivals;

    public OnVehicleAtDepot(JdbcVehicleArrivals arrivals) {
      this.arrivals = arrivals;
    }

    @Override
    public String consumerName() {
      return "loading.on-vehicle-at-depot";
    }

    @Override
    public Class<VehicleAtDepot> eventType() {
      return VehicleAtDepot.class;
    }

    @Override
    public void on(EventEnvelope<VehicleAtDepot> envelope) {
      VehicleAtDepot e = envelope.payload();
      arrivals.record(e.vehicleId(), e.depotCode(), e.serviceDate(), e.driverId(), e.arrivedAt());
    }
  }
}
