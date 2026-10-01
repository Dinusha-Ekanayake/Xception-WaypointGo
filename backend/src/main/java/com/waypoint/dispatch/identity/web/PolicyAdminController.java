package com.waypoint.dispatch.identity.web;

import com.waypoint.dispatch.identity.application.PolicyAdminUseCase;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Policy administration.
 *
 * <p>Every method authorizes through the decision point before doing anything,
 * including the read. Policy is exactly the thing an attacker would want to
 * read first, so {@code iam:ReadPolicy} is a permission like any other.
 *
 * <p>Thin: it resolves the session, asks whether the action is permitted, and
 * delegates. It decides nothing itself.
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

  public record CreateRequest(String name, String description, Object document) {}

  public record DocumentRequest(Object document) {}

  public record AttachRequest(String principalType, String principalId) {}

  @GetMapping
  public Page<PolicyAdminUseCase.PolicySummary> list(
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    authorize(request, "iam:ReadPolicy", "wpt:iam:policy:*");
    return policies.list(after, limit);
  }

  @PostMapping
  public ResponseEntity<Map<String, String>> create(
      @RequestBody CreateRequest body, HttpServletRequest request) {
    Actor actor = authorize(request, "iam:CreatePolicy", "wpt:iam:policy:" + body.name());
    var id = policies.createPolicy(actor, body.name(), body.description(), json(body.document()));
    return ResponseEntity.status(201).body(Map.of("policyId", id.toString()));
  }

  @PostMapping("/{name}/versions")
  public Map<String, Integer> addVersion(
      @PathVariable String name, @RequestBody DocumentRequest body, HttpServletRequest request) {
    Actor actor = authorize(request, "iam:CreatePolicy", "wpt:iam:policy:" + name);
    return Map.of("versionNumber", policies.createVersion(actor, name, json(body.document())));
  }

  @PutMapping("/{name}/default-version/{versionNumber}")
  public ResponseEntity<Void> setDefault(
      @PathVariable String name, @PathVariable int versionNumber, HttpServletRequest request) {
    Actor actor = authorize(request, "iam:CreatePolicy", "wpt:iam:policy:" + name);
    policies.setDefaultVersion(actor, name, versionNumber);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{name}/attachments")
  public ResponseEntity<Void> attach(
      @PathVariable String name, @RequestBody AttachRequest body, HttpServletRequest request) {
    Actor actor = authorize(request, "iam:AttachPolicy", "wpt:iam:policy:" + name);
    policies.attach(actor, name, body.principalType(), body.principalId());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{name}/attachments/{principalType}/{principalId}")
  public ResponseEntity<Void> detach(
      @PathVariable String name,
      @PathVariable String principalType,
      @PathVariable String principalId,
      HttpServletRequest request) {
    Actor actor = authorize(request, "iam:AttachPolicy", "wpt:iam:policy:" + name);
    policies.detach(actor, name, principalType, principalId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Shared with every other read surface. It used to be a private copy here, which
   * is one copy away from a controller that forgets to audit its denials.
   */
  private Actor authorize(HttpServletRequest request, String action, String resource) {
    return authorizer.require(request, action, resource);
  }

  private static String json(Object document) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document);
    } catch (Exception e) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Policy document is not valid JSON");
    }
  }
}
