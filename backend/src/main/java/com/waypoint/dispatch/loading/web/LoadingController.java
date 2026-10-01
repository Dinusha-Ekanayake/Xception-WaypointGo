package com.waypoint.dispatch.loading.web;

import com.waypoint.dispatch.loading.application.LoadingDataQuery;
import com.waypoint.dispatch.loading.contract.LoadingViews.ManifestView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ReadyTripView;
import com.waypoint.dispatch.loading.contract.LoadingViews.ShortfallView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading dock work. Taking, checking, flagging, handing back and releasing are
 * commands through {@code POST /api/commands} (or {@code /api/sync} when they
 * were queued offline), never endpoints here.
 *
 * <p>Policy decides {@code loading:Read}; row-level security decides which
 * trips (effective access is policy AND scope).
 */
@RestController
@RequestMapping("/api/loading")
public class LoadingController {
  private static final String READ = LoadingDataQuery.READ;

  private final LoadingDataQuery loading;
  private final RequestAuthorizer authorizer;

  public LoadingController(LoadingDataQuery loading, RequestAuthorizer authorizer) {
    this.loading = loading;
    this.authorizer = authorizer;
  }

  /** Tonight's departures for a depot (Figma 01 Dock board). */
  @GetMapping("/trips")
  public List<ReadyTripView> trips(
      @RequestParam String depot,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:loading:depot:" + depot);
    return loading.readyTrips(actor, depot, date);
  }

  /** One trip's load sheet on its current plan version (Figma 02). */
  @GetMapping("/trips/{tripId}/manifest")
  public ManifestView manifest(@PathVariable UUID tripId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:loading:trip:" + tripId);
    return loading.manifest(actor, tripId);
  }

  /** Flagged items for a depot, newest first. */
  @GetMapping("/shortfalls")
  public List<ShortfallView> shortfalls(
      @RequestParam String depot,
      @RequestParam(defaultValue = "true") boolean open,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:loading:depot:" + depot);
    return loading.shortfalls(actor, depot, open);
  }
}
