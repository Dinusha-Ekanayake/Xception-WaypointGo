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

  // ---- the permission editor (R-IAM-42) -------------------------------------

  @GetMapping("/{name}/versions")
  public java.util.List<PolicyAdminUseCase.VersionView> versions(
      @org.springframework.web.bind.annotation.PathVariable String name, HttpServletRequest request) {
    authorizer.require(request, "iam:ReadPolicy", "wpt:iam:policy:" + name);
    return policies.versions(name);
  }

  @GetMapping("/{name}/versions/{version}")
  public com.fasterxml.jackson.databind.JsonNode version(
      @org.springframework.web.bind.annotation.PathVariable String name,
      @org.springframework.web.bind.annotation.PathVariable int version, HttpServletRequest request) throws Exception {
    authorizer.require(request, "iam:ReadPolicy", "wpt:iam:policy:" + name);
    return MAPPER.readTree(policies.document(name, version));
  }

  @GetMapping("/{name}/attachments")
  public java.util.List<PolicyAdminUseCase.Attachment> attachments(
      @org.springframework.web.bind.annotation.PathVariable String name, HttpServletRequest request) {
    authorizer.require(request, "iam:ReadPolicy", "wpt:iam:policy:" + name);
    return policies.attachments(name);
  }

  /**
   * What saving {@code document} as the policy's new default would change, for
   * each person it reaches. Read only: nothing is written, so it is not a
   * command; only someone who may author policy may ask.
   */
  @org.springframework.web.bind.annotation.PostMapping("/{name}/preview-change")
  public PolicyAdminUseCase.Preview preview(
      @org.springframework.web.bind.annotation.PathVariable String name,
      @org.springframework.web.bind.annotation.RequestBody com.fasterxml.jackson.databind.JsonNode body,
      HttpServletRequest request) {
    authorizer.require(request, "iam:CreatePolicy", "wpt:iam:policy:" + name);
    return policies.preview(name, body.path("document").toString());
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
}
