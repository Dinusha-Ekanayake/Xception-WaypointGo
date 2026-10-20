package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.StoreContactQuery;
import com.waypoint.dispatch.identity.application.StoreContactQuery.StoreContact;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An outlet's store managers to call or message about an order (R-IAM-41). */
@RestController
@RequestMapping("/api/outlets")
public class StoreContactController {
  private final StoreContactQuery contacts;
  private final RequestAuthorizer authorizer;

  public StoreContactController(StoreContactQuery contacts, RequestAuthorizer authorizer) {
    this.contacts = contacts;
    this.authorizer = authorizer;
  }

  @GetMapping("/{outletId}/contacts")
  public List<StoreContact> contacts(@PathVariable String outletId, HttpServletRequest request) {
    var actor = authorizer.require(request, "iam:ReadStoreContact", "wpt:iam:outlet:" + outletId);
    return contacts.of(actor, outletId);
  }
}
