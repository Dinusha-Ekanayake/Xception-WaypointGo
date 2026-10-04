package com.waypoint.dispatch.execution.web;

import com.waypoint.dispatch.execution.application.ExecutionDataQuery;
import com.waypoint.dispatch.execution.application.PositionSignals;
import com.waypoint.dispatch.execution.application.PositionsQuery;
import com.waypoint.dispatch.execution.contract.ExecutionViews.TrailPointView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.VehiclePositionView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live map reads. Positions are written only by {@code delivery:RecordPositions}
 * through the command bus. Policy decides {@code delivery:Read}; scope and
 * row-level security decide which vehicles and points.
 */
@RestController
@RequestMapping("/api/execution")
public class PositionsController {
  private static final String READ = ExecutionDataQuery.READ;

  /** Browsers reconnect an event stream on their own; a bounded one frees the request thread. */
  private static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);

  private final PositionsQuery positions;
  private final PositionSignals signals;
  private final RequestAuthorizer authorizer;

  public PositionsController(PositionsQuery positions, PositionSignals signals, RequestAuthorizer authorizer) {
    this.positions = positions;
    this.signals = signals;
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

  /**
   * The same positions as they change (R-EXE-23), as server-sent events named
   * {@code positions}: once on connect, about a second after new fixes, and
   * every 20 seconds regardless. Scope is checked before the stream opens, so an
   * out-of-scope request is a {@code 403} with an audit row, never an empty
   * stream. A client that hears nothing for longer falls back to polling
   * {@code /positions} and says so on screen (rule 9).
   */
  @GetMapping(path = "/positions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String depot,
      @RequestParam(required = false) String outlet,
      HttpServletRequest request,
      HttpServletResponse response) {
    if ((depot == null) == (outlet == null)) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Give exactly one of depot or outlet");
    }
    PositionSignals.Watch watch;
    Actor actor;
    if (depot != null) {
      actor = authorizer.require(request, READ, "wpt:execution:depot:" + depot);
      positions.ofDepot(actor, depot, date);
      watch = PositionSignals.Watch.depot(depot, date);
    } else {
      actor = authorizer.require(request, READ, "wpt:execution:outlet:" + outlet);
      positions.forOutlet(actor, outlet, date);
      watch = PositionSignals.Watch.outlet(outlet, date);
    }
    // nginx would otherwise buffer the small events and hold them back (issue #118).
    response.setHeader("X-Accel-Buffering", "no");
    SseEmitter emitter = new SseEmitter(STREAM_LIFETIME.toMillis());
    Runnable stop =
        signals.listen(actor, watch, list -> emitter.send(SseEmitter.event().name("positions").data(list)));
    emitter.onCompletion(stop);
    emitter.onTimeout(stop);
    emitter.onError(e -> stop.run());
    return emitter;
  }

  /**
   * A trip's points, oldest first, on a keyset cursor. {@code since} (an instant,
   * exclusive) starts after a point the caller already holds, so a live map asks
   * only for what is new; a cursor, when given, wins over it.
   */
  @GetMapping("/trips/{tripId}/trail")
  public Page<TrailPointView> trail(
      @PathVariable UUID tripId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:execution:trip:" + tripId);
    return positions.trail(actor, tripId, Optional.ofNullable(cursor), Optional.ofNullable(since), limit);
  }
}
