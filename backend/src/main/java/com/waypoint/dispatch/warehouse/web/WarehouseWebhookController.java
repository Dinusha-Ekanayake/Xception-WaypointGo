package com.waypoint.dispatch.warehouse.web;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.warehouse.application.InboundEvents;
import com.waypoint.dispatch.warehouse.application.InboundEvents.Landing;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/integrations/warehouse/events}: the warehouse's webhook.
 *
 * <p>No session: the HMAC signature is the authentication, checked over the raw
 * bytes. The request is landed in the inbox and answered at once; processing is
 * asynchronous. An unverified request is still stored, quarantined, and answered
 * {@code 401} (SEC-18). A replay is a no-op (SEC-19). With no secret configured
 * the endpoint does not exist ({@code 404}), and polling covers the gap.
 */
@RestController
public class WarehouseWebhookController {
  private final InboundEvents inbound;

  public WarehouseWebhookController(InboundEvents inbound) {
    this.inbound = inbound;
  }

  @PostMapping(path = "/api/integrations/warehouse/events", consumes = "*/*")
  public ResponseEntity<Map<String, Object>> receive(
      @RequestBody(required = false) byte[] body,
      @RequestHeader(name = "X-Warehouse-Timestamp", required = false) String timestamp,
      @RequestHeader(name = "X-Warehouse-Signature", required = false) String signature) {
    if (!inbound.enabled()) {
      throw new DomainException(ErrorCode.NOT_FOUND, "The warehouse webhook is not enabled");
    }
    Landing landing = inbound.land(body == null ? new byte[0] : body, timestamp, signature);
    return switch (landing) {
      case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("received", true));
      case DUPLICATE -> ResponseEntity.ok(Map.of("received", true, "duplicate", true));
      case UNVERIFIED -> throw new DomainException(
          ErrorCode.UNAUTHENTICATED, "Webhook signature missing or invalid; stored and quarantined");
    };
  }
}
