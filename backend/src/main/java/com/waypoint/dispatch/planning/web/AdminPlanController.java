package com.waypoint.dispatch.planning.web;

import com.waypoint.dispatch.planning.application.PlanDataQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.platform.web.RequestValues;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only plan directory; details remain at GET /api/plans/{id}. */
@RestController
@RequestMapping("/api/admin/plans")
public class AdminPlanController {
  private final PlanDataQuery plans;
  private final RequestAuthorizer authorizer;

  public AdminPlanController(PlanDataQuery plans, RequestAuthorizer authorizer) {
    this.plans = plans;
    this.authorizer = authorizer;
  }

  @GetMapping
  public Page<PlanView> list(@RequestParam(required = false) String depot,
      @RequestParam(required = false) String date, @RequestParam(required = false) String status,
      @RequestParam(required = false) String after, @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, PlanDataQuery.READ, "wpt:plan:plan:*");
    return plans.adminPage(actor, depot, RequestValues.optionalDate("date", date), status, after, limit);
  }
}
