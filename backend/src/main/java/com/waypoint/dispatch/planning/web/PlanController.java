package com.waypoint.dispatch.planning.web;

import com.waypoint.dispatch.planning.application.PlanDataQuery;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationPageView;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanSummaryView;
import com.waypoint.dispatch.planning.contract.PlanViews.DeferralView;
import com.waypoint.dispatch.planning.contract.PlanViews.FuelView;
import com.waypoint.dispatch.planning.contract.PlanViews.InterchangePreview;
import com.waypoint.dispatch.planning.contract.PlanViews.PlacementView;
import com.waypoint.dispatch.planning.contract.PlanViews.ComparisonView;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.SnapshotDetailView;
import com.waypoint.dispatch.planning.contract.PlanViews.SnapshotView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripPreview;
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

  /** The open draft for a depot and day; 404 while none is open. */
  @GetMapping("/draft")
  public PlanView draft(
      @RequestParam String depot, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:depot:" + depot);
    return plans.workingDraft(actor, depot, date);
  }

  /** The published plan without its allocations (issue #177); 404 while none is published. */
  @GetMapping("/published/summary")
  public PlanSummaryView publishedSummary(
      @RequestParam String depot, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:depot:" + depot);
    return plans.publishedSummary(actor, depot, date);
  }

  /** The open draft without its allocations; 404 while none is open. */
  @GetMapping("/draft/summary")
  public PlanSummaryView draftSummary(
      @RequestParam String depot, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:depot:" + depot);
    return plans.draftSummary(actor, depot, date);
  }

  /** One keyset page of a plan's allocations, in order id order. */
  @GetMapping("/{planId}/allocations")
  public AllocationPageView allocations(
      @PathVariable UUID planId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false, defaultValue = "50") int limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:plan:" + planId);
    return plans.allocationPage(actor, planId, cursor(after), limit);
  }

  private static java.util.Optional<UUID> cursor(String after) {
    if (after == null || after.isBlank()) {
      return java.util.Optional.empty();
    }
    try {
      return java.util.Optional.of(UUID.fromString(after));
    } catch (IllegalArgumentException e) {
      throw new com.waypoint.dispatch.shared.error.DomainException(
          com.waypoint.dispatch.shared.error.ErrorCode.VALIDATION_FAILED, "after is not a cursor from this list");
    }
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

  /** The same places, each naming the vehicle and trip number a {@code plan:Override} sends. */
  @GetMapping("/preview/placements")
  public List<PlacementView> previewPlacements(@RequestParam UUID order, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:order:" + order);
    return plans.previewPlacements(actor, order);
  }

  /** Whether a substitute vehicle could take a trip whole, checked against the whole registry. */
  @GetMapping("/preview/interchange")
  public InterchangePreview previewInterchange(
      @RequestParam UUID trip, @RequestParam String vehicle, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:trip:" + trip);
    return plans.previewInterchange(actor, trip, vehicle);
  }

  /** The trip a swap would leave, and every rule's verdict on the vehicle's day. */
  @GetMapping("/preview/swap")
  public TripPreview previewSwap(@RequestParam UUID out, @RequestParam("in") UUID in, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:order:" + out);
    return plans.previewSwap(actor, out, in);
  }

  /** A trip with its stops in the order given (every order of it, comma separated), timed and checked. */
  @GetMapping("/preview/sequence")
  public TripPreview previewSequence(
      @RequestParam UUID trip, @RequestParam List<UUID> orders, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:trip:" + trip);
    return plans.previewSequence(actor, trip, orders);
  }

  /** The saved plans of a depot and day, newest first. */
  @GetMapping("/snapshots")
  public List<SnapshotView> snapshots(
      @RequestParam String depot, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:depot:" + depot);
    return plans.snapshots(actor, depot, date);
  }

  /** One saved plan with the plan itself, read only. */
  @GetMapping("/snapshots/{snapshotId}")
  public SnapshotDetailView snapshot(@PathVariable UUID snapshotId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:snapshot:" + snapshotId);
    return plans.snapshot(actor, snapshotId);
  }

  /** Two plans of one depot and day side by side; each id is a saved plan or a plan version. */
  @GetMapping("/compare")
  public ComparisonView compare(@RequestParam UUID a, @RequestParam UUID b, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:plan:" + a);
    return plans.compare(actor, a, b);
  }

  /** Weekly fuel for the ISO week containing {@code date}, published plans only (D-K). */
  @GetMapping("/fuel")
  public FuelView fuel(
      @RequestParam String vehicle, @RequestParam LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:plan:vehicle:" + vehicle);
    return plans.fuelRemaining(actor, vehicle, date);
  }
}
