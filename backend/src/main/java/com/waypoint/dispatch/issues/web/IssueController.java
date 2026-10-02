package com.waypoint.dispatch.issues.web;

import com.waypoint.dispatch.issues.application.IssueDataQuery;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueHistoryView;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueView;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading issues. Raising, assigning, resolving, recording a replacement,
 * scheduling a redelivery, closing and cancelling are commands through
 * {@code POST /api/commands}, never endpoints here.
 *
 * <p>Policy decides {@code issue:Read}; row-level security decides which issues
 * (effective access is policy AND scope).
 */
@RestController
@RequestMapping("/api/issues")
public class IssueController {
  private static final String READ = IssueDataQuery.READ;

  private final IssueDataQuery issues;
  private final RequestAuthorizer authorizer;

  public IssueController(IssueDataQuery issues, RequestAuthorizer authorizer) {
    this.issues = issues;
    this.authorizer = authorizer;
  }

  /** A depot's open and assigned issues, most severe first, on a keyset cursor. */
  @GetMapping
  public Page<IssueView> open(
      @RequestParam String depot,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:issue:depot:" + depot);
    return issues.openIssues(actor, depot, Optional.ofNullable(after), limit);
  }

  /** Every issue about one order, trip, delivery, receipt, shortfall or vehicle that the actor can see. */
  @GetMapping("/by-subject")
  public List<IssueView> bySubject(
      @RequestParam String type, @RequestParam String id, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:issue:" + type + ":" + id);
    return issues.issuesFor(actor, new SubjectRef(type, id));
  }

  @GetMapping("/{issueId}")
  public IssueView issue(@PathVariable UUID issueId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:issue:issue:" + issueId);
    return issues.issue(actor, issueId);
  }

  @GetMapping("/{issueId}/history")
  public List<IssueHistoryView> history(@PathVariable UUID issueId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:issue:issue:" + issueId);
    return issues.history(actor, issueId);
  }
}
