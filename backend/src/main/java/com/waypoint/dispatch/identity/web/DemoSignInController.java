package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.DemoSignInQuery;
import com.waypoint.dispatch.identity.application.DemoSignInQuery.Account;
import com.waypoint.dispatch.platform.observability.Metrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read before sign-in, so it needs no session: the demo accounts to pick from
 * and the loaders' demo PIN. Outside a configured demo it answers an empty list,
 * the same as a demo with nothing to offer, so it says nothing about the setup.
 */
@RestController
@RequestMapping("/api/session")
public class DemoSignInController {
  private final DemoSignInQuery query;
  private final Metrics metrics;

  public DemoSignInController(DemoSignInQuery query, Metrics metrics) {
    this.query = query;
    this.metrics = metrics;
  }

  @GetMapping("/demo")
  public Map<String, Object> demo() {
    Map<String, Object> body = new LinkedHashMap<>();
    query.view().ifPresentOrElse(
        demo -> {
          // Each time the demo password leaves, it is counted (alert when nobody is presenting).
          metrics.increment("waypoint.demo.signin.served");
          body.put("accounts", demo.accounts());
          body.put("password", demo.password());
          body.put("pin", demo.pin().orElse(null));
        },
        () -> {
          body.put("accounts", List.<Account>of());
          body.put("password", null);
          body.put("pin", null);
        });
    return body;
  }
}
