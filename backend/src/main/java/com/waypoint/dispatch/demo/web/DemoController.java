package com.waypoint.dispatch.demo.web;

import com.waypoint.dispatch.demo.application.DemoSettingsQuery;
import com.waypoint.dispatch.demo.contract.DemoView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
  private final RequestAuthorizer auth;
  private final DemoSettingsQuery settings;

  public DemoController(RequestAuthorizer auth, DemoSettingsQuery settings) {
    this.auth = auth;
    this.settings = settings;
  }

  @GetMapping
  public DemoView settings(HttpServletRequest request) {
    auth.require(request, "demo:Read", "wpt:demo:settings:global");
    return settings.view();
  }

  @GetMapping("/scenario-runs")
  public List<Map<String, Object>> runs(HttpServletRequest request,
      @RequestParam(required = false) java.time.Instant beforeStartedAt,
      @RequestParam(required = false) UUID beforeId) {
    UUID actor = auth.require(request, "demo:Manage", "wpt:demo:runs:all").userId();
    return settings.runs(actor, beforeStartedAt, beforeId);
  }

  @GetMapping("/simulations")
  public List<Map<String, Object>> simulations(HttpServletRequest request,
      @RequestParam(required = false) java.time.Instant beforeStartedAt,
      @RequestParam(required = false) UUID beforeId) {
    UUID actor = auth.require(request, "demo:Manage", "wpt:demo:runs:all").userId();
    return settings.simulations(actor, beforeStartedAt, beforeId);
  }
}
