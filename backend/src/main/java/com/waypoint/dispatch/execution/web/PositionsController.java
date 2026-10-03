package com.waypoint.dispatch.execution.web;

import com.waypoint.dispatch.execution.application.ExecutionDataQuery;
import com.waypoint.dispatch.execution.application.PositionsQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.TrailPointView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.VehiclePositionView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live map reads. Positions are written only by {@code delivery:RecordPositions}
 * through the command bus. Policy decides {@code delivery:Read}; scope and
 * row-level security decide which vehicles and points.
 */
@RestController
@RequestMapping("/api/execution")
public class PositionsController {
  private static final String READ = ExecutionDataQuery.READ;

  private final PositionsQuery positions;
  private final RequestAuthorizer authorizer;

  public PositionsController(PositionsQuery positions, RequestAuthorizer authorizer) {
    this.positions = positions;
    this.authorizer = authorizer;
  }

  /** Each vehicle's last good fix: by depot for a dispatcher, by outlet for a store manager. */
  @GetMapping("/positions")
  public List<VehiclePositionView> positions(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String depot,
      @RequestParam(required = false) String outlet,
      HttpServletRequest request) {
    if ((depot == null) == (outlet == null)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Give exactly one of depot or outlet");
    }
    if (depot != null) {
      var actor = authorizer.require(request, READ, "wpt:execution:depot:" + depot);
      return positions.ofDepot(actor, depot, date);
    }
    var actor = authorizer.require(request, READ, "wpt:execution:outlet:" + outlet);
    return positions.forOutlet(actor, outlet, date);
  }

  @GetMapping("/trips/{tripId}/trail")
  public Page<TrailPointView> trail(
      @PathVariable UUID tripId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:execution:trip:" + tripId);
    return positions.trail(actor, tripId, Optional.ofNullable(cursor), limit);
  }
}
