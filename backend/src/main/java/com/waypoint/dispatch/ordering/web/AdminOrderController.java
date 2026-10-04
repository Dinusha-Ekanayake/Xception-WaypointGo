package com.waypoint.dispatch.ordering.web;

import com.waypoint.dispatch.ordering.application.OrderDataQuery;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.platform.web.RequestValues;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, SQL-scoped order directory for the admin console. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {
  private final OrderDataQuery orders;
  private final RequestAuthorizer authorizer;

  public AdminOrderController(OrderDataQuery orders, RequestAuthorizer authorizer) {
    this.orders = orders;
    this.authorizer = authorizer;
  }

  @GetMapping
  public Page<OrderView> list(@RequestParam(required = false) String depot,
      @RequestParam(required = false) String date, @RequestParam(required = false) String status,
      @RequestParam(required = false) String brand, @RequestParam(required = false) String outlet,
      @RequestParam(required = false) String temperature, @RequestParam(required = false) String search,
      @RequestParam(required = false) String after, @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, OrderDataQuery.READ, "wpt:order:order:*");
    return orders.adminPage(actor, depot, RequestValues.optionalDate("date", date), status,
        brand, outlet, temperature, search, after, limit);
  }
}
