package com.waypoint.dispatch.receipt.web;

import com.waypoint.dispatch.platform.web.RequestAuthorizer;
import com.waypoint.dispatch.receipt.application.ReceiptDataQuery;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.CustodyChainView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.HandoverView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.PendingReceiptView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptAnswerView;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading receipts. Confirming, confirming in part and disputing are commands
 * through {@code POST /api/commands}, never endpoints here.
 *
 * <p>Policy decides {@code receipt:Read}; row-level security decides which
 * outlets and depots (effective access is policy AND scope).
 */
@RestController
@RequestMapping("/api/receipts")
public class ReceiptController {
  private static final String READ = ReceiptDataQuery.READ;

  private final ReceiptDataQuery receipts;
  private final RequestAuthorizer authorizer;

  public ReceiptController(ReceiptDataQuery receipts, RequestAuthorizer authorizer) {
    this.receipts = receipts;
    this.authorizer = authorizer;
  }

  /** Deliveries waiting for the store's answer, oldest first. */
  @GetMapping("/pending")
  public List<PendingReceiptView> pending(@RequestParam String outlet, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:receipt:outlet:" + outlet);
    return receipts.pendingConfirmations(actor, outlet);
  }

  /** The receipt for an order; 404 before the delivery is recorded or outside scope. */
  @GetMapping("/{orderId}")
  public ReceiptView receipt(@PathVariable UUID orderId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:receipt:order:" + orderId);
    return receipts.receipt(actor, orderId);
  }

  /** Where the handover PIN stands, never the PIN itself (R-RCP-09); 404 when none was issued or outside scope. */
  @GetMapping("/{orderId}/handover")
  public HandoverView handover(@PathVariable UUID orderId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:receipt:order:" + orderId);
    return receipts.handover(actor, orderId);
  }

  /** The store's answer for the driver who handed it over; 404 until the store answers, or outside scope. */
  @GetMapping("/{orderId}/answer")
  public ReceiptAnswerView answer(@PathVariable UUID orderId, HttpServletRequest request) {
    var actor = authorizer.require(request, ReceiptDataQuery.READ_ANSWER, "wpt:receipt:order:" + orderId);
    return receipts.answer(actor, orderId);
  }

  /** The loading check, the proof and the receipt side by side (R-RCP-08), for disputes and audit. */
  @GetMapping("/{orderId}/custody")
  public CustodyChainView custody(@PathVariable UUID orderId, HttpServletRequest request) {
    var actor = authorizer.require(request, READ, "wpt:receipt:order:" + orderId);
    return receipts.custodyChain(actor, orderId);
  }
}
