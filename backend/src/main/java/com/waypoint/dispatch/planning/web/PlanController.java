package com.waypoint.dispatch.planning.web;

import com.waypoint.dispatch.planning.application.PlanDataQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.DeferralView;
import com.waypoint.dispatch.planning.contract.PlanViews.FuelView;
import com.waypoint.dispatch.planning.contract.PlanViews.InterchangePreview;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading plans. Generating, overriding, deferring, publishing and revising are
 * commands through {@code POST /api/commands}, never endpoints here.
 *
 * <p>Policy decides {@code plan:Read}; row-level security decides which depots
 * (effective access is policy AND scope).
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {
  private static final String READ = PlanDataQuery.READ;

  private final PlanDataQuery plans;
  private final RequestAuthorizer authorizer;

  public PlanController(PlanDataQuery plans, RequestAuthorizer authorizer) {
    this.plans = plans;
    this.authorizer = authorizer;
  }

  /** The current published plan for a depot and day; 404 while none is published. */
  @GetMapping("/published")
  public PlanView published(
      @RequestParam String depot, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:depot:" + depot);
    return plans.publishedPlan(actor, depot, date);
  }

  /** Any version, draft to superseded, with every allocation's constraint results. */
  @GetMapping("/{planId}")
  public PlanView plan(@PathVariable UUID planId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:plan:" + planId);
    return plans.plan(actor, planId);
  }

  @GetMapping("/deferrals")
  public List<DeferralView> deferrals(
      @RequestParam String depot, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:depot:" + depot);
    return plans.deferralsFor(actor, depot, date);
  }

  /** Where an order could go in its open draft, each place with every check (the override screen). */
  @GetMapping("/preview/assignments")
  public List<AllocationView> previewAssignments(@RequestParam UUID order, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:order:" + order);
    return plans.previewAssignments(actor, order);
  }

  /** Whether a substitute vehicle could take a trip whole, checked against the whole registry. */
  @GetMapping("/preview/interchange")
  public InterchangePreview previewInterchange(
      @RequestParam UUID trip, @RequestParam String vehicle, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:trip:" + trip);
    return plans.previewInterchange(actor, trip, vehicle);
  }

  /** Weekly fuel for the ISO week containing {@code date}, published plans only (D-K). */
  @GetMapping("/fuel")
  public FuelView fuel(
      @RequestParam String vehicle, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:vehicle:" + vehicle);
    return plans.fuelRemaining(actor, vehicle, date);
  }
}
