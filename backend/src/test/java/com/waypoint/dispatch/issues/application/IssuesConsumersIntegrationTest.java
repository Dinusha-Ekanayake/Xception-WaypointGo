package com.waypoint.dispatch.issues.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryFailed;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.VehicleFaultReported;
import com.waypoint.dispatch.issues.infrastructure.JdbcIssueRepository;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingShortfall;
import com.waypoint.dispatch.loading.contract.LoadingViews.CheckStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptConfirmed;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseDiscrepancyFound;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Problems reported by other modules becoming issues, and the escalation timer,
 * through a stand-in for the relay and the scheduler (#6).
 */
class IssuesConsumersIntegrationTest extends ReceiptIssuesSupport {
  @Autowired IssueEscalationJob escalation;
  @Autowired JdbcIssueRepository issues;

  @Test
  void eachReportedProblemRaisesExactlyOneIssueWithTheDefaultSeverity() throws Exception {
    Order order = deliveredOrder(outlet);
    UUID shortfall = UUID.randomUUID();
    LoadingShortfall event =
        new LoadingShortfall(shortfall, UUID.randomUUID(), order.orderId(), depot, CheckStatus.MISSING, 2, "carton missing");

    UUID eventId = deliver("issues.on-loading-shortfall", event);
    redeliver("issues.on-loading-shortfall", eventId, event, Optional.empty());
    deliver("issues.on-loading-shortfall", event);

    JsonNode raised = read(dispatcher, "/api/issues/by-subject?type=shortfall&id=" + shortfall, 200);
    assertEquals(1, raised.size(), "a redelivered or repeated event raises nothing new");
    assertEquals("LOADING_SHORTFALL", raised.get(0).get("type").asText());
    assertEquals("HIGH", raised.get(0).get("severity").asText());
    assertEquals(outlet.outletId(), raised.get(0).get("outletId").asText(), "the outlet comes from Ordering");

    String issueId = raised.get(0).get("issueId").asText();
    send(dispatcher,
        envelope("issue:RecordReplacement", 1L,
            "{\"issueId\":\"" + issueId + "\",\"tripId\":\"" + event.tripId() + "\",\"orderId\":\""
                + order.orderId() + "\",\"note\":\"carton from the reserve\"}"),
        200);
    JsonNode resolved = mapper.readTree(String.valueOf(outbox(issueId, "shortfall.resolved").get(0).get("payload")));
    assertEquals(shortfall.toString(), resolved.get("shortfallId").asText(),
        "Loading resolves just this shortfall, not every open one of the trip and order");
  }

  @Test
  void aFailedDeliveryAVehicleFaultAndAStockDiscrepancyBecomeIssues() throws Exception {
    Order order = deliveredOrder(outlet);
    UUID delivery = UUID.randomUUID();
    deliver("issues.on-delivery-failed",
        new DeliveryFailed(delivery, order.orderId(), UUID.randomUUID(), order.outletId(), depot, "outlet closed",
            Instant.now()));
    String vehicle = "VEH-T" + UUID.randomUUID().toString().substring(0, 6);
    deliver("issues.on-vehicle-fault-reported",
        new VehicleFaultReported(vehicle, depot, LocalDate.now(), "brakes", Instant.now()));
    deliver("issues.on-warehouse-discrepancy-found",
        new WarehouseDiscrepancyFound(order.orderId(), depot, "confirmed", "cancelled", "cancelled outside Waypoint"));

    assertEquals("FAILED_DELIVERY", read(dispatcher, "/api/issues/by-subject?type=delivery&id=" + delivery, 200)
        .get(0).get("type").asText());
    JsonNode fault = read(dispatcher, "/api/issues/by-subject?type=vehicle&id=" + vehicle, 200).get(0);
    assertEquals("CRITICAL", fault.get("severity").asText());
    assertEquals(1, fault.get("subjects").size(), "decision 11: a vehicle alone is a valid subject");
    Set<String> types =
        StreamSupport.stream(
                read(dispatcher, "/api/issues/by-subject?type=order&id=" + order.orderId(), 200).spliterator(), false)
            .map(i -> i.get("type").asText())
            .collect(Collectors.toSet());
    assertEquals(Set.of("FAILED_DELIVERY", "STOCK_DISCREPANCY"), types, "STK-11");
  }

  @Test
  void aPartialReceiptOpensOneInvestigationLinkedToTheWholeCustodyChain() throws Exception {
    Order order = deliveredOrder(outlet);
    deliver("receipt.on-delivery-completed", completed(order, Instant.now()), driver.id());
    JsonNode partial =
        send(manager,
            envelope("receipt:ConfirmPartial", 1L,
                "{\"orderId\":\"" + order.orderId() + "\",\"lines\":[{\"productId\":\"P-1\",\"receivedQuantity\":6}]}"),
            200).get("result");
    UUID receiptId = UUID.fromString(partial.get("receiptId").asText());
    ReceiptConfirmed confirmed = new ReceiptConfirmed(receiptId, order.orderId(), order.outletId(), true, Instant.now());

    deliver("issues.on-receipt-confirmed", confirmed);
    deliver("issues.on-receipt-confirmed", confirmed);

    JsonNode found = read(dispatcher, "/api/issues/by-subject?type=receipt&id=" + receiptId, 200);
    assertEquals(1, found.size(), "one investigation per receipt");
    JsonNode investigation = found.get(0);
    assertEquals("RECEIPT_DISPUTE", investigation.get("type").asText());
    Set<String> linked =
        StreamSupport.stream(investigation.get("subjects").spliterator(), false)
            .map(s -> s.get("type").asText())
            .collect(Collectors.toSet());
    assertEquals(Set.of("order", "receipt", "delivery", "trip"), linked, "RCP-06: check, proof and receipt");
    assertTrue(
        database.asSystem(ModuleRole.ISSUES, () -> issues.find(UUID.fromString(investigation.get("issueId").asText())))
            .orElseThrow().issue().investigation());

    ReceiptConfirmed full = new ReceiptConfirmed(UUID.randomUUID(), order.orderId(), order.outletId(), false, Instant.now());
    deliver("issues.on-receipt-confirmed", full);
    assertEquals(0, read(dispatcher, "/api/issues/by-subject?type=receipt&id=" + full.receiptId(), 200).size(),
        "a full confirmation is not a problem");
  }

  @Test
  void anUnassignedIssueEscalatesOnceAtItsSeverityDeadline() throws Exception {
    UUID delivery = UUID.randomUUID();
    Order order = deliveredOrder(outlet);
    deliver("issues.on-delivery-failed",
        new DeliveryFailed(delivery, order.orderId(), UUID.randomUUID(), order.outletId(), depot, "refused",
            Instant.now()));
    UUID issueId =
        UUID.fromString(read(dispatcher, "/api/issues/by-subject?type=delivery&id=" + delivery, 200).get(0)
            .get("issueId").asText());
    Instant raisedAt = stored(issueId).raisedAt();

    escalation.runAt(raisedAt.plus(Duration.ofMinutes(59)));
    assertTrue(stored(issueId).escalatedAt().isEmpty(), "HIGH waits sixty minutes");

    escalation.runAt(raisedAt.plus(Duration.ofMinutes(60)));
    assertEquals(Optional.of(raisedAt.plus(Duration.ofMinutes(60))), stored(issueId).escalatedAt());
    assertEquals(1, outbox(issueId.toString(), "issue.escalated").size(), "Notification routes it (R-ISS-06)");
    JsonNode escalatedEvent =
        mapper.readTree(String.valueOf(outbox(issueId.toString(), "issue.escalated").get(0).get("payload")));
    assertEquals(60, escalatedEvent.get("waitedMinutes").asLong());
    long version = stored(issueId).rowVersion();
    escalation.runAt(raisedAt.plus(Duration.ofHours(3)));
    assertEquals(version, stored(issueId).rowVersion(), "escalates once");
    assertEquals(1, outbox(issueId.toString(), "issue.escalated").size(), "and announces once");
    JsonNode history = read(dispatcher, "/api/issues/" + issueId + "/history", 200);
    assertEquals("escalated", history.get(history.size() - 1).get("action").asText());
    assertFalse(history.get(history.size() - 1).get("reason").asText().isBlank());
  }

  private com.waypoint.dispatch.issues.domain.Issue stored(UUID issueId) {
    return database.asSystem(ModuleRole.ISSUES, () -> issues.find(issueId).orElseThrow().issue());
  }
}
