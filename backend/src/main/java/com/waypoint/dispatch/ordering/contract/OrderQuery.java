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
}
