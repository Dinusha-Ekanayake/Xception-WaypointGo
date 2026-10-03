package com.waypoint.dispatch.referencedata.web;

import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.platform.web.RequestValues;
import com.waypoint.dispatch.referencedata.application.OutletDetailsQuery;
import com.waypoint.dispatch.referencedata.application.ReferenceScope;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.CalendarDayView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.VehicleView;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading outlets, fleet and the calendar.
 *
 * <p>Every endpoint takes an optional {@code version}. Omitting it means the
 * version currently in use; naming one replays exactly what that version held,
 * which is what makes a past decision reproducible rather than merely recorded.
 * The loaded version is exposed so a stale instance is visible on screen instead
 * of quietly answering from last week's data.
 *
 * <p>Reads only. Importing a version and overriding a calendar day are both
 * commands, because both are decisions that need a receipt and an audit row.
 *
 * <p>Authorization goes through the {@code RequestAuthorizer} port rather than the
 * identity module directly: a module's web layer may not import another module.
 * That is the policy half. A depot's outlets and fleet are then checked against
 * the actor's own scope by {@link ReferenceScope} (R-IAM-28); the version and the
 * calendar are the same for everyone.
 */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {
  private static final String READ = "reference:Read";

  private final ReferenceQuery reference;
  private final RequestAuthorizer authorizer;
  private final ReferenceScope scope;
  private final OutletDetailsQuery details;

  public ReferenceController(
      ReferenceQuery reference,
      RequestAuthorizer authorizer,
      ReferenceScope scope,
      OutletDetailsQuery details) {
    this.reference = reference;
    this.authorizer = authorizer;
    this.scope = scope;
    this.details = details;
  }

  /** What every other answer on this controller was computed from. */
  @GetMapping("/version")
  public Map<String, String> version(HttpServletRequest request) {
    authorizer.require(request, READ, "wpt:ref:version:*");
    return Map.of("versionId", reference.currentVersionId().map(UUID::toString).orElse(""));
  }

  @GetMapping("/outlets")
  public Page<OutletView> outletsOfDepot(
      @RequestParam String depot,
      @RequestParam(required = false) String version,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    String resource = "wpt:ref:depot:" + depot;
    scope.requireDepot(authorizer.require(request, READ, resource), READ, resource, depot);
    return Page.slice(
        reference.outletsOfDepot(depot, RequestValues.optionalUuid("version", version)),
        after,
        limit,
        OutletView::outletId);
  }

  @GetMapping("/outlets/{outletId}")
  public OutletView outlet(
      @PathVariable String outletId,
      @RequestParam(required = false) String version,
      HttpServletRequest request) {
    String resource = "wpt:ref:outlet:" + outletId;
    Actor actor = authorizer.require(request, READ, resource);
    OutletView outlet =
        reference
            .outlet(outletId, RequestValues.optionalUuid("version", version))
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No outlet " + outletId));
    scope.requireOutlet(actor, READ, resource, outlet);
    return outlet;
  }

  /**
   * R-REF-01: what the store says about itself, with the version a change names.
   * Readable by whoever may read the outlet, so a driver can find the store's
   * number; changed only with {@code reference:UpdateOutletDetails}.
   */
  @GetMapping("/outlets/{outletId}/details")
  public OutletDetailsQuery.OutletDetailsView outletDetails(
      @PathVariable String outletId, HttpServletRequest request) {
    String resource = "wpt:ref:outlet:" + outletId;
    Actor actor = authorizer.require(request, READ, resource);
    OutletView outlet =
        reference
            .outlet(outletId, null)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No outlet " + outletId));
    scope.requireOutlet(actor, READ, resource, outlet);
    return details.of(outletId);
  }

  /**
   * The fleet a depot can actually use on a date: vehicles minus workshop and
   * unavailable. Answering with the whole fleet and letting the caller filter is
   * how a broken truck gets planned.
   */
  @GetMapping("/vehicles")
  public Page<VehicleView> availableVehicles(
      @RequestParam String depot,
      @RequestParam String date,
      @RequestParam(required = false) String version,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    String resource = "wpt:ref:depot:" + depot;
    scope.requireDepot(authorizer.require(request, READ, resource), READ, resource, depot);
    return Page.slice(
        reference.availableVehicles(
            depot, RequestValues.date("date", date), RequestValues.optionalUuid("version", version)),
        after,
        limit,
        VehicleView::vehicleId);
  }

  @GetMapping("/vehicles/{vehicleId}")
  public VehicleView vehicle(
      @PathVariable String vehicleId,
      @RequestParam(required = false) String version,
      HttpServletRequest request) {
    String resource = "wpt:ref:vehicle:" + vehicleId;
    Actor actor = authorizer.require(request, READ, resource);
    VehicleView vehicle =
        reference
            .vehicle(vehicleId, RequestValues.optionalUuid("version", version))
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No vehicle " + vehicleId));
    scope.requireVehicle(actor, READ, resource, vehicle);
    return vehicle;
  }

  /**
   * Answers even for a date past the end of the supplied calendar, where the day
   * is generated by policy and says so. R-CAL-03: a dispatcher has to be able to
   * tell a real holiday from an assumed one, so {@code generated} is in the body.
   */
  @GetMapping("/calendar/{date}")
  public Map<String, Object> day(@PathVariable String date, HttpServletRequest request) {
    authorizer.require(request, READ, "wpt:ref:calendar:" + date);
    LocalDate on = RequestValues.date("date", date);
    CalendarDayView view = reference.day(on).orElse(null);
    return Map.of(
        "date", on.toString(),
        "operating", reference.isOperating(on),
        "nextOperatingDay", reference.nextOperatingDay(on).toString(),
        "known", view != null,
        "day", view == null ? Map.of() : view);
  }


}
