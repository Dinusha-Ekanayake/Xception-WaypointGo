package com.waypoint.dispatch.ordering.web;

import com.waypoint.dispatch.ordering.application.DeliveryDateResolver;
import com.waypoint.dispatch.ordering.application.OrderDataQuery;
import com.waypoint.dispatch.ordering.application.RideAlongQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.DemandView;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.ordering.contract.OrderViews.RideAlongView;
import com.waypoint.dispatch.ordering.contract.OrderViews.StatusChangeView;
import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading orders. Placing, amending, cancelling and closing a day are commands
 * through {@code POST /api/commands}, never endpoints here.
 *
 * <p>Policy decides {@code order:Read}; row-level security decides which orders
 * (effective access is policy AND scope).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
  private static final String READ = OrderDataQuery.READ;

  private final OrderDataQuery orders;
  private final DeliveryDateResolver dates;
  private final RideAlongQuery rideAlong;
  private final ReferenceQuery reference;
  private final RequestAuthorizer authorizer;
  private final Clock clock;

  public OrderController(
      OrderDataQuery orders,
      DeliveryDateResolver dates,
      RideAlongQuery rideAlong,
      ReferenceQuery reference,
      RequestAuthorizer authorizer,
      Clock clock) {
    this.orders = orders;
    this.dates = dates;
    this.rideAlong = rideAlong;
    this.reference = reference;
    this.authorizer = authorizer;
    this.clock = clock;
  }

  @GetMapping
  public Page<OrderView> ordersForOutlet(
      @RequestParam String outlet,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") int limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:order:outlet:" + outlet);
    return orders.ordersForOutlet(actor, outlet, Optional.ofNullable(cursor), limit);
  }

  @GetMapping("/{orderId}")
  public OrderView order(@PathVariable UUID orderId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:order:order:" + orderId);
    return orders.order(actor, orderId);
  }

  @GetMapping("/{orderId}/timeline")
  public List<StatusChangeView> timeline(@PathVariable UUID orderId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:order:order:" + orderId);
    return orders.timeline(actor, orderId);
  }

  /** Every order due at a depot on a day, in any status, for the dispatcher's order board. */
  @GetMapping("/day")
  public List<OrderView> ordersForDay(
      @RequestParam String depot, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:order:depot:" + depot);
    return orders.ordersForDay(actor, depot, date);
  }

  /** What Planning will see for a depot-day, for the dispatcher's demand screen. */
  @GetMapping("/demand")
  public List<DemandView> demand(
      @RequestParam String depot, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:order:depot:" + depot);
    return orders.confirmedDemand(actor, depot, date);
  }

  /**
   * The date an order asked for {@code requestedDate} would be served, and why
   * it moved, so the store sees the roll before submitting rather than after (D-I).
   */
  @GetMapping("/delivery-date")
  public DeliveryDate deliveryDate(
      @RequestParam String outlet,
      @RequestParam LocalDate requestedDate,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:order:outlet:" + outlet);
    OutletView o =
        reference
            .outlet(outlet, null)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No outlet " + outlet));
    return orders.asActor(actor, () -> dates.resolve(o.depotCode(), requestedDate, clock.now()));
  }

  /**
   * Nearby open days whose trip already serves the outlet's district, so the
   * store can choose to join it (R-ORD-13, issue #199). Advice only.
   */
  @GetMapping("/ride-along")
  public RideAlongView rideAlong(
      @RequestParam String outlet,
      @RequestParam LocalDate requestedDate,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:order:outlet:" + outlet);
    OutletView o =
        reference
            .outlet(outlet, null)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No outlet " + outlet));
    return rideAlong.suggest(actor, o, requestedDate, clock.now());
  }
}
