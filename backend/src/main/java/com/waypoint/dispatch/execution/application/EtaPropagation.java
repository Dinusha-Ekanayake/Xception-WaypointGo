package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.EtaChanged;
import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.EtaPolicy;
import com.waypoint.dispatch.execution.domain.EtaPolicy.StopTimes;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.TripStop;
import com.waypoint.dispatch.platform.messaging.EventPublisher;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Works out the expected arrival of a trip's stops still to come and tells each
 * store whose time moved ten minutes or more (R-EXE-15, roadmap T1-03). Called
 * inside the transaction of the arrival or the delivery that moved it, and by
 * {@link EtaRefreshJob} for a vehicle held up on the road. The stored expected
 * arrival is what the store was last told, so telling is idempotent.
 */
@Component
public class EtaPropagation {
  private final JdbcDeliveryRepository deliveries;
  private final EventPublisher events;
  private final Metrics metrics;

  EtaPropagation(JdbcDeliveryRepository deliveries, EventPublisher events, Metrics metrics) {
    this.deliveries = deliveries;
    this.events = events;
    this.metrics = metrics;
  }

  /** @return how many stores were told a new time */
  public int refresh(Actor actor, UUID tripId, Instant now) {
    List<TripStop> stops = deliveries.tripStops(tripId);
    Map<UUID, Instant> expected =
        EtaPolicy.propagate(stops.stream().map(EtaPropagation::times).toList(), now);
    int told = 0;
    for (TripStop stop : stops) {
      DeliveryRecord r = stop.record();
      Instant eta = expected.get(r.deliveryId());
      if (eta == null || !EtaPolicy.worthAnnouncing(eta, stop.expectedArrival().orElse(r.plannedArrivalAt()))) {
        continue;
      }
      deliveries.expect(r.deliveryId(), eta);
      events.publish(
          actor,
          new EtaChanged(r.deliveryId(), r.orderId(), r.outletId(), eta, EtaPolicy.delayMinutes(eta, r.plannedArrivalAt())));
      told++;
    }
    if (told > 0) {
      metrics.increment("waypoint.execution.eta_changed");
    }
    return told;
  }

  static StopTimes times(TripStop stop) {
    DeliveryRecord r = stop.record();
    return new StopTimes(
        r.deliveryId(),
        r.plannedArrivalAt(),
        r.serviceDate().atTime(r.window().open()).atZone(Clock.OPERATING_ZONE).toInstant(),
        stop.plannedServiceMinutes(),
        r.arrivedAt(),
        r.isFinished() ? r.completedAt() : java.util.Optional.empty(),
        r.isFinished());
  }
}
