package com.waypoint.dispatch.ordering.contract;

import com.waypoint.dispatch.ordering.contract.OrderViews.DemandView;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.ordering.contract.OrderViews.StatusChangeView;
import com.waypoint.dispatch.shared.domain.Page;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way another module reads orders. Scope is applied in SQL by the
 * implementation, never by filtering a broad read (architecture rule 7).
 */
public interface OrderQuery {

  /**
   * Orders Planning may allocate for a depot and service day: {@code CONFIRMED}
   * and {@code DEFERRED} orders due that day. {@code STOCK_UNKNOWN} orders are
   * excluded, because stock is never assumed (R-STK-05).
   */
  List<DemandView> confirmedDemand(String depotCode, LocalDate serviceDate);

  Optional<OrderView> order(UUID orderId);

  /** Oldest first. */
  List<StatusChangeView> timeline(UUID orderId);

  Page<OrderView> ordersForOutlet(String outletId, Optional<String> cursor, int limit);

  /**
   * Orders placed per day for a depot and brand, by placement date in the
   * operating zone, cancelled ones excluded (issue #16: the deterministic demand
   * forecast). Chilled volume counts only chilled orders.
   */
  List<OrderViews.DailyVolumeView> dailyVolumes(
      String depotCode, String brandCode, LocalDate from, LocalDate to);

  /**
   * Volume already booked per delivery day and brand for a depot: orders still
   * to be delivered, by the day they are due (issue #224, the date outlook).
   * Totals only, read as the system across every outlet of the depot, so the
   * caller checks the asking actor's scope first and never passes a row on
   * (R-ML-07).
   */
  List<OrderViews.BookedVolumeView> bookedVolumes(String depotCode, LocalDate from, LocalDate to);

  /**
   * Orders booked for a day and not yet on a plan, for the date outlook watch
   * (issue #224, slice 3): ids, outlet, brand and day only. Read as the system;
   * the caller is a job, never a person.
   */
  List<OrderViews.OpenOrderView> openOrders(String depotCode, LocalDate from, LocalDate to);
}
