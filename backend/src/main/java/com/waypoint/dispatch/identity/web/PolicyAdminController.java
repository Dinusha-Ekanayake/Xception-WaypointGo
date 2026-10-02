package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.PolicyAdminUseCase;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading policy.
 *
 * <p>Reads only, and deliberately, like the account surface beside it. Every
 * change to a policy is a command through {@code POST /api/commands}
 * ({@code iam:CreatePolicy}, {@code iam:CreatePolicyVersion},
 * {@code iam:SetDefaultPolicyVersion}, {@code iam:AttachPolicy},
 * {@code iam:DetachPolicy}), so it gets an idempotency receipt, a version guard
 * and an audit row that commit with it.
 *
 * <p>The read is authorized through the decision point like everything else.
 * Policy is exactly the thing an attacker would want to read first, so
 * {@code iam:ReadPolicy} is a permission like any other. Each row carries
 * {@code rowVersion}, which is what a caller sends back as {@code expectedVersion}.
 */
@RestController
@RequestMapping("/api/policies")
public class PolicyAdminController {
  private final PolicyAdminUseCase policies;
  private final RequestAuthorizer authorizer;

  public PolicyAdminController(PolicyAdminUseCase policies, RequestAuthorizer authorizer) {
    this.policies = policies;
    this.authorizer = authorizer;
  }

  @GetMapping
  public Page<PolicyAdminUseCase.PolicySummary> list(
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    authorizer.require(request, "iam:ReadPolicy", "wpt:iam:policy:*");
    return policies.list(after, limit);
  }
}
