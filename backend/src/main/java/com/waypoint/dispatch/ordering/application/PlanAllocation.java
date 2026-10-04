package com.waypoint.dispatch.ordering.application;

import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A published plan's trips, applied to their orders as {@code ALLOCATED}.
 *
 * <p>Shared by {@code plan.published} and {@code plan.revised}: a revision is a
 * new version that rebuilds from its own trips, so an order already allocated
 * to a superseded trip is moved to the new one rather than treated as a repeat.
 */
@Component
class PlanAllocation {
  private final JdbcOrderRepository orders;
  private final OrderTransitions transitions;

  PlanAllocation(JdbcOrderRepository orders, OrderTransitions transitions) {
    this.orders = orders;
    this.transitions = transitions;
  }

  void allocate(List<PlannedTrip> trips, int planVersion, java.time.LocalDate serviceDate, EventEnvelope<?> envelope) {
    for (PlannedTrip trip : trips) {
      for (PlannedStop stop : trip.stops()) {
        Optional<Order> found = orders.find(stop.orderId());
        if (found.isEmpty()) {
          transitions.unknown(stop.orderId(), envelope);
          continue;
        }
        // Issue #224: where the order stands on the plan, for the store to see; a revision rewrites it.
        orders.recordStop(
            stop.orderId(), trip.tripId(), stop.sequence(), stop.plannedArrival(), serviceDate, planVersion,
            envelope.occurredAt());
        Order current = found.get();
        String reason = "allocated to trip " + trip.tripId() + " in plan version " + planVersion;
        if (current.status() == OrderStatus.ALLOCATED) {
          if (!current.tripId().equals(Optional.of(trip.tripId()))) {
            transitions.write(current, current.allocateTo(trip.tripId()), "re" + reason, envelope);
          }
          continue;
        }
        transitions.apply(
            current.orderId(), OrderStatus.ALLOCATED, o -> o.allocateTo(trip.tripId()), reason, envelope);
      }
    }
  }
}
