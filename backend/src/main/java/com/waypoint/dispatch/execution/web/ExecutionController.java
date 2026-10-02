package com.waypoint.dispatch.execution.web;

import com.waypoint.dispatch.execution.application.ExecutionDataQuery;
import com.waypoint.dispatch.execution.application.ProofLinks;
import com.waypoint.dispatch.execution.application.ProofUploads;
import com.waypoint.dispatch.execution.application.ProofUploads.Receipt;
import com.waypoint.dispatch.execution.contract.ExecutionCommands;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryRecordView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.ProofView;
import com.waypoint.dispatch.execution.contract.ExecutionViews.RunSheetView;
import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
 * Reading what happened on the road, and taking in proof artifacts. Starting a
 * stop, arriving, recording an outcome, capturing proof and reporting a fault
 * are commands through {@code POST /api/commands} (or {@code /api/sync} when
 * they were queued offline), never endpoints here.
 *
 * <p>Policy decides {@code delivery:Read}; row-level security decides which
 * stops (effective access is policy AND scope).
 */
@RestController
@RequestMapping("/api/execution")
public class ExecutionController {
  private static final String READ = ExecutionDataQuery.READ;

  private final ExecutionDataQuery execution;
  private final ProofUploads uploads;
  private final ProofLinks links;
  private final RequestAuthorizer authorizer;

  public ExecutionController(
      ExecutionDataQuery execution, ProofUploads uploads, ProofLinks links, RequestAuthorizer authorizer) {
    this.execution = execution;
    this.uploads = uploads;
    this.links = links;
    this.authorizer = authorizer;
  }

  /**
   * Run sheets for a day. With {@code depot}, every vehicle on the road from
   * that depot (the dispatcher's live view); without, the vehicles the caller
   * drives (the driver's day).
   */
  @GetMapping("/run-sheets")
  public List<RunSheetView> runSheets(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) String depot,
      HttpServletRequest request) {
    if (depot == null || depot.isBlank()) {
      var actor = authorizer.require(request, READ, "wpt:execution:vehicle:*");
      return execution.myRunSheets(actor, date);
    }
    var actor = authorizer.require(request, READ, "wpt:execution:depot:" + depot);
    return execution.runSheetsOfDepot(actor, depot, date);
  }

  /** The vehicles the caller is assigned to drive on a date, before any trip has left. */
  @GetMapping("/vehicles")
  public List<String> vehicles(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:execution:vehicle:*");
    return execution.myVehicles(actor, date);
  }

  /** What is coming to, or has reached, an outlet on a day; or the latest attempt at one order. */
  @GetMapping("/deliveries")
  public List<DeliveryRecordView> deliveries(
      @RequestParam(required = false) String outlet,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) UUID order,
      HttpServletRequest request) {
    if (order != null) {
      var actor = authorizer.require(request, READ, "wpt:execution:order:" + order);
      return List.of(execution.deliveryForOrder(actor, order));
    }
    if (outlet == null || outlet.isBlank() || date == null) {
      throw new DomainException(ErrorCode.VALIDATION_FAILED, "Ask for order, or for outlet and date");
    }
    var actor = authorizer.require(request, READ, "wpt:execution:outlet:" + outlet);
    return execution.deliveriesForOutlet(actor, outlet, date);
  }

  @GetMapping("/deliveries/{deliveryId}")
  public DeliveryRecordView delivery(@PathVariable UUID deliveryId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:execution:delivery:" + deliveryId);
    return execution.delivery(actor, deliveryId);
  }

  /** The proof for a delivery, with links that open its artifacts for a few minutes. */
  @GetMapping("/deliveries/{deliveryId}/proof")
  public ProofView proof(@PathVariable UUID deliveryId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:execution:delivery:" + deliveryId);
    return execution.proof(actor, deliveryId);
  }

  /**
   * Stores a proof photo or signature. The id is minted on the device, so the
   * same upload sent twice is one artifact. PUT, because it is: this id, this
   * content.
   */
  @PutMapping(
      path = "/deliveries/{deliveryId}/attachments/{attachmentId}",
      consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp", MediaType.APPLICATION_OCTET_STREAM_VALUE})
  public Receipt upload(
      @PathVariable UUID deliveryId,
      @PathVariable UUID attachmentId,
      @RequestParam String kind,
      @RequestBody byte[] content,
      HttpServletRequest request) {
    var actor =
        authorizer.require(request, ExecutionCommands.CAPTURE_PROOF, "wpt:execution:delivery:" + deliveryId);
    return uploads.store(actor, deliveryId, attachmentId, kind, content);
  }

  /**
   * The artifact behind a signed link. The signature is the authorization: it
   * was minted for someone allowed to see the delivery, covers this artifact
   * and this expiry only, and an absent, expired or forged link all answer the
   * same 404.
   */
  @GetMapping("/attachments/{attachmentId}/content")
  public ResponseEntity<byte[]> content(
      @PathVariable UUID attachmentId, @RequestParam long exp, @RequestParam String sig) {
    if (!links.opens(attachmentId, exp, sig)) {
      throw new DomainException(ErrorCode.NOT_FOUND, "This link has expired or is not valid");
    }
    var content =
        uploads.content(attachmentId)
            .orElseThrow(() -> new DomainException(ErrorCode.NOT_FOUND, "This link has expired or is not valid"));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "inline")
        .body(content.bytes());
  }
}
