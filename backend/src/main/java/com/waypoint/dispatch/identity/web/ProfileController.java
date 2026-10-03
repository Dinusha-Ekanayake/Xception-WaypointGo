package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.AccountQuery;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A person's own profile (R-IAM-32): read here, changed with
 * {@code iam:UpdateOwnProfile} through the command endpoint like every write.
 *
 * <p>Gated on the same action as the change, on {@code wpt:iam:user:self}: a role
 * that may not edit its profile has no reason to fetch it, and the phone number
 * on it is personal data. The account read is always the actor's, so there is no
 * identifier in the path to try another one with.
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {
  private final AccountQuery accounts;
  private final RequestAuthorizer authorizer;

  public ProfileController(AccountQuery accounts, RequestAuthorizer authorizer) {
    this.accounts = accounts;
    this.authorizer = authorizer;
  }

  @GetMapping
  public AccountQuery.ProfileView mine(HttpServletRequest request) {
    Actor actor = authorizer.require(request, "iam:UpdateOwnProfile", "wpt:iam:user:self");
    return accounts.profile(actor.userId());
  }
}
