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

  /** A trip's stores in one read: {@code ?ids=OUT001,OUT002}, each outlet checked like the single read. */
  @GetMapping("/contacts")
  public java.util.Map<String, List<StoreContact>> ofOutlets(
      @org.springframework.web.bind.annotation.RequestParam String ids, HttpServletRequest request) {
    List<String> outlets = java.util.Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    if (outlets.isEmpty() || outlets.size() > StoreContactQuery.MAX_OUTLETS) {
      throw new com.waypoint.dispatch.shared.error.DomainException(
          com.waypoint.dispatch.shared.error.ErrorCode.VALIDATION_FAILED,
          "Name between 1 and " + StoreContactQuery.MAX_OUTLETS + " outlets");
    }
    var actor = authorizer.require(request, "iam:ReadStoreContact", "wpt:iam:outlet:" + outlets.get(0));
    for (String outlet : outlets.subList(1, outlets.size())) {
      authorizer.require(request, "iam:ReadStoreContact", "wpt:iam:outlet:" + outlet);
    }
    return contacts.ofMany(actor, outlets);
  }

  @GetMapping("/{outletId}/contacts")
  public List<StoreContact> contacts(@PathVariable String outletId, HttpServletRequest request) {
    var actor = authorizer.require(request, "iam:ReadStoreContact", "wpt:iam:outlet:" + outletId);
    return contacts.of(actor, outletId);
  }
}
