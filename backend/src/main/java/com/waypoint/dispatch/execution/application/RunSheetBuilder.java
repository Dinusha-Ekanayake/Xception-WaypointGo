package com.waypoint.dispatch.execution.application;

import com.waypoint.dispatch.execution.domain.DeliveryRecord;
import com.waypoint.dispatch.execution.domain.ServiceWindow;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository;
import com.waypoint.dispatch.execution.infrastructure.JdbcDeliveryRepository.TripRow;
import com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.shared.util.UuidV7;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalTime;
import org.springframework.stereotype.Component;

/**
 * Turns a released trip into the driver's run sheet: one delivery record per
 * order, in stop order.
 *
 * <p>Called inside the relay's transaction, as the system actor. The trip and
 * its stops come from the event alone; the unit count is read once from
 * Ordering's contract, and the outlet's window from Reference's, and both are
 * copied onto the record so nothing that changes later rewrites this stop.
 *
 * <p>Safe to repeat: a trip already built is left exactly as it is, whatever
 * has been recorded against it since.
 */
@Component
public class RunSheetBuilder {
  /** Reference's code for an outlet that takes goods only inside its mall's window (R-PLN-14). */
  private static final String MALL_DOCK = "mall_dock";

  private final JdbcDeliveryRepository deliveries;
  private final OrderQuery orders;
  private final ReferenceQuery reference;
  private final Metrics metrics;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public RunSheetBuilder(
      JdbcDeliveryRepository deliveries,
      OrderQuery orders,
      ReferenceQuery reference,
      Metrics metrics,
      Clock clock) {
    this.deliveries = deliveries;
    this.orders = orders;
    this.reference = reference;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** @return stops created; zero when the trip was already built */
  public int build(TripReleased released) {
    Instant now = clock.now();
    boolean isNew =
        deliveries.insertTrip(
            new TripRow(
                released.tripId(), released.planId(), released.planVersion(), released.depotCode(),
                released.vehicleId(), released.serviceDate()),
            now);
    if (!isNew) {
      return 0;
    }
    for (ReleasedStop stop : released.stops()) {
      OrderView order =
          orders.order(stop.orderId())
              .orElseThrow(() -> new IllegalStateException("Released trip names unknown order " + stop.orderId()));
      OutletView outlet =
          reference.outlet(stop.outletId(), null)
              .orElseThrow(() -> new IllegalStateException("Released trip names unknown outlet " + stop.outletId()));
      // The window the outlet will actually accept: for a mall outlet, the
      // overlap with the mall's own (R-PLN-29), already resolved by Reference.
      ServiceWindow window =
          new ServiceWindow(
              outlet.effectiveWindowOpen().orElse(outlet.windowOpen()),
              outlet.effectiveWindowClose().orElse(outlet.windowClose()));
      LocalTime planned = stop.plannedArrival() == null ? window.open() : stop.plannedArrival();
      deliveries.insertRecord(
          DeliveryRecord.released(
              UuidV7.generate(now, random), released.tripId(), stop.orderId(), stop.outletId(),
              released.depotCode(), released.vehicleId(), released.serviceDate(), stop.sequence(),
              order.itemCount(), planned, window, MALL_DOCK.equals(outlet.parkingConstraint())),
          now);
    }
    metrics.increment("waypoint.execution.run_sheets_built", "depot", released.depotCode());
    return released.stops().size();
  }
}
