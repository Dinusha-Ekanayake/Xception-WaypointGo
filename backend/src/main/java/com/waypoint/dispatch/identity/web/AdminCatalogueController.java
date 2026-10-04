package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.AdminCatalogueQuery;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only catalogues for the administrator's access screen. */
@RestController
@RequestMapping("/api/admin")
public class AdminCatalogueController {
  private final AdminCatalogueQuery catalogue;
  private final RequestAuthorizer authorizer;

  public AdminCatalogueController(AdminCatalogueQuery catalogue, RequestAuthorizer authorizer) {
    this.catalogue = catalogue;
    this.authorizer = authorizer;
  }

  @GetMapping("/roles")
  public Page<AdminCatalogueQuery.RoleView> roles(
      @RequestParam(required = false) String after, @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    authorizer.require(request, "iam:ReadPolicy", "wpt:iam:policy:*");
    return catalogue.roles(after, limit);
  }

  @GetMapping("/actions")
  public Page<AdminCatalogueQuery.ActionView> actions(
      @RequestParam(required = false) String after, @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    authorizer.require(request, "iam:ReadPolicy", "wpt:iam:policy:*");
    return catalogue.actions(after, limit);
  }
}
