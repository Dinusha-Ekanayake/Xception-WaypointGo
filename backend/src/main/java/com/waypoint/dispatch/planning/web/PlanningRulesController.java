package com.waypoint.dispatch.planning.web;

import com.waypoint.dispatch.planning.application.PlanningRuleCatalogue;
import com.waypoint.dispatch.planning.contract.PlanningRuleViews;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative read; changes go through planning:CreateRuleSet on the command bus. */
@RestController
@RequestMapping("/api/admin/constraints/planning")
public class PlanningRulesController {
  private final PlanningRuleCatalogue catalogue;
  private final RequestAuthorizer authorizer;

  public PlanningRulesController(PlanningRuleCatalogue catalogue, RequestAuthorizer authorizer) {
    this.catalogue = catalogue;
    this.authorizer = authorizer;
  }

  @GetMapping
  public PlanningRuleViews.Catalogue read(HttpServletRequest request) {
    var actor = authorizer.require(request, "planning:ReadRules", "wpt:planning:rules:*");
    return catalogue.read(actor);
  }
}
