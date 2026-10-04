package com.waypoint.dispatch.referencedata.web;

import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.platform.web.RequestValues;
import com.waypoint.dispatch.referencedata.application.AdminReferenceQuery;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scoped administration directories. Reads the current published reference version. */
@RestController
@RequestMapping("/api/admin")
public class AdminReferenceController {
  private final AdminReferenceQuery references;
  private final RequestAuthorizer authorizer;

  public AdminReferenceController(AdminReferenceQuery references, RequestAuthorizer authorizer) {
    this.references = references;
    this.authorizer = authorizer;
  }

  @GetMapping("/reference/depots")
  public List<AdminReferenceQuery.Depot> depots(HttpServletRequest request) {
    var actor = authorizer.require(request, "reference:Read", "wpt:ref:depot:*");
    return references.depots(actor.userId());
  }

  @GetMapping("/reference/depots/{code}")
  public AdminReferenceQuery.DepotDetail depot(@PathVariable String code, HttpServletRequest request) {
    var actor = authorizer.require(request, "reference:Read", "wpt:ref:depot:" + code);
    return references.depot(actor.userId(), code);
  }

  @GetMapping("/outlets")
  public Page<AdminReferenceQuery.Outlet> outlets(
      @RequestParam(required = false) String depot, @RequestParam(required = false) String brand,
      @RequestParam(required = false) String district, @RequestParam(required = false) String dockType,
      @RequestParam(required = false) String search, @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit, HttpServletRequest request) {
    var actor = authorizer.require(request, "reference:Read", "wpt:ref:outlet:*");
    return references.outlets(actor.userId(), depot, brand, district, dockType, search, after, limit);
  }

  @GetMapping("/outlets/{id}")
  public AdminReferenceQuery.Outlet outlet(@PathVariable String id, HttpServletRequest request) {
    var actor = authorizer.require(request, "reference:Read", "wpt:ref:outlet:" + id);
    return references.outlet(actor.userId(), id);
  }

  @GetMapping("/vehicles")
  public Page<AdminReferenceQuery.Vehicle> vehicles(
      @RequestParam(required = false) String depot, @RequestParam(required = false) String date,
      @RequestParam(required = false) String type, @RequestParam(required = false) String temperature,
      @RequestParam(required = false) String status, @RequestParam(required = false) String search,
      @RequestParam(required = false) String after, @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, "reference:Read", "wpt:ref:vehicle:*");
    return references.vehicles(actor.userId(), depot, RequestValues.optionalDate("date", date), type,
        temperature, status, search, after, limit);
  }

  @GetMapping("/vehicles/{id}")
  public AdminReferenceQuery.Vehicle vehicle(@PathVariable String id,
      @RequestParam(required = false) String date, HttpServletRequest request) {
    var actor = authorizer.require(request, "reference:Read", "wpt:ref:vehicle:" + id);
    return references.vehicle(actor.userId(), id, RequestValues.optionalDate("date", date));
  }
}
