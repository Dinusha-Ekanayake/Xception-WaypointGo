package com.waypoint.dispatch.issues.web;

import com.waypoint.dispatch.issues.application.IssueAttachments;
import com.waypoint.dispatch.issues.application.IssueAttachments.Receipt;
import com.waypoint.dispatch.issues.application.IssueDataQuery;
import com.waypoint.dispatch.issues.contract.IssueCommands;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueHistoryView;
import com.waypoint.dispatch.issues.contract.IssueViews.IssueView;
import com.waypoint.dispatch.issues.contract.IssueViews.SubjectRef;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading issues, and the photos of a delivery problem. Raising, assigning,
 * resolving, recording a replacement, scheduling a redelivery, closing and
 * cancelling are commands through {@code POST /api/commands}, never endpoints
 * here. A photo is binary, so it is the one write here: idempotent by its id.
 *
 * <p>Policy decides {@code issue:Read}; row-level security decides which issues
 * (effective access is policy AND scope).
 */
@RestController
@RequestMapping("/api/issues")
public class IssueController {
  private static final String READ = IssueDataQuery.READ;

  private final IssueDataQuery issues;
  private final IssueAttachments attachments;
  private final RequestAuthorizer authorizer;

  public IssueController(IssueDataQuery issues, IssueAttachments attachments, RequestAuthorizer authorizer) {
    this.issues = issues;
    this.attachments = attachments;
    this.authorizer = authorizer;
  }

  /**
   * Stores a photo of a delivery problem for an order, and for its receipt when it was taken while
   * counting. The id is minted on the phone, so the same upload sent twice is one photo. PUT,
   * because it is: this id, this content.
   */
  @PutMapping(
      path = "/attachments/{attachmentId}",
      consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp", MediaType.APPLICATION_OCTET_STREAM_VALUE})
  public Receipt upload(
      @PathVariable UUID attachmentId,
      @RequestParam UUID order,
      @RequestParam(required = false) UUID receipt,
      @RequestBody byte[] content,
      HttpServletRequest request) {
    var actor = authorizer.require(request, IssueCommands.ATTACH_PHOTO, "wpt:issue:order:" + order);
    return attachments.store(actor, attachmentId, order, Optional.ofNullable(receipt), content);
  }

  /** A photo of an issue the actor can see. Absent, out of scope and cleared all answer the same 404. */
  @GetMapping("/{issueId}/attachments/{attachmentId}/content")
  public ResponseEntity<byte[]> photo(
      @PathVariable UUID issueId, @PathVariable UUID attachmentId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:issue:issue:" + issueId);
    var photo =
        attachments.content(actor, issueId, attachmentId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "No such photo on this issue"));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(photo.contentType()))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "inline")
        .body(photo.bytes());
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
