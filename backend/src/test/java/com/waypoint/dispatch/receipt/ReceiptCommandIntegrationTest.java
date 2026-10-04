package com.waypoint.dispatch.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The store's answers end to end through {@code POST /api/commands}: the bus
 * authorizes, the handler decides, and the receipt, its history, its event, the
 * receipt row and the audit row commit together.
 */
class ReceiptCommandIntegrationTest extends ReceiptIssuesSupport {

  @Test
  void aConfirmationIsRecordedOnceHoweverOftenItIsSent() throws Exception {
    Order order = delivered();
    String body = confirm(order.orderId(), 1L);

    JsonNode first = send(manager, body, 200);
    assertEquals("CONFIRMED", first.get("result").get("status").asText());
    JsonNode again = send(manager, body, 200);
    assertTrue(again.get("replayed").asBoolean(), "a double tap returns the original");

    String receiptId = first.get("result").get("receiptId").asText();
    assertEquals(1, outbox(receiptId, "receipt.confirmed").size());
    assertFalse(payload(outbox(receiptId, "receipt.confirmed").get(0)).get("partial").asBoolean());
    assertEquals("CONFIRMED", read(manager, "/api/receipts/" + order.orderId(), 200).get("status").asText());
  }

  @Test
  void aPartialReceiptRecordsTheShortageAndIsAnnouncedAsPartial() throws Exception {
    Order order = delivered();

    JsonNode result =
        send(manager, partial(order.orderId(), 1L, "[{\"productId\":\"P-1\",\"receivedQuantity\":7}]"), 200)
            .get("result");

    assertEquals("PARTIAL", result.get("status").asText());
    assertFalse(result.get("late").asBoolean());
    JsonNode view = read(manager, "/api/receipts/" + order.orderId(), 200);
    assertEquals(7, view.get("lines").get(0).get("receivedQuantity").asInt());
    assertTrue(
        payload(outbox(result.get("receiptId").asText(), "receipt.confirmed").get(0)).get("partial").asBoolean(),
        "RCP-01: Issues opens its investigation from this");
  }

  @Test
  void aDisputeIsAnnouncedAndOrderingRecordsTheOrderAsReceived() throws Exception {
    Order order = delivered();

    JsonNode result =
        send(manager, dispute(order.orderId(), 1L, "seal broken on arrival", "[]"), 200).get("result");

    assertEquals("DISPUTED", result.get("status").asText());
    assertEquals(1, outbox(result.get("receiptId").asText(), "receipt.disputed").size());
    var disputed =
        new com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptDisputed(
            UUID.fromString(result.get("receiptId").asText()), order.orderId(), order.outletId(), depot,
            "seal broken on arrival", Instant.now());
    deliver("ordering.on-receipt-disputed", disputed);
    assertEquals(OrderStatus.RECEIVED, orderStatus(order.orderId()), "decision 1: received, disputed");
  }

  @Test
  void aStaleVersionIsRefused() throws Exception {
    Order order = delivered();
    send(manager, partial(order.orderId(), 1L, "[{\"productId\":\"P-2\",\"receivedQuantity\":1}]"), 200);

    JsonNode stale = send(manager, confirm(order.orderId(), 1L), 409);

    assertEquals("VERSION_CONFLICT", stale.get("code").asText(), stale.toString());
  }

  @Test
  void anotherOutletsManagerIsForbiddenAndAudited() throws Exception {
    Order order = delivered();
    long before = denials(stranger.id(), "receipt:Confirm");

    send(stranger, confirm(order.orderId(), 1L), 403);

    assertEquals(before + 1, denials(stranger.id(), "receipt:Confirm"), "RCP-05: 403 plus audit");
    assertEquals("PENDING", read(manager, "/api/receipts/" + order.orderId(), 200).get("status").asText());
  }

  @Test
  void aDispatcherCannotAnswerForTheStore() throws Exception {
    Order order = delivered();
    send(dispatcher, confirm(order.orderId(), 1L), 403);
  }

  @Test
  void nothingCanBeConfirmedForADeliveryThatWasNeverRecorded() throws Exception {
    Order order = deliveredOrder(outlet);

    JsonNode refused = send(manager, confirm(order.orderId(), 1L), 404);

    assertTrue(refused.toString().contains("RCP-04"), refused.toString());
  }

  // ---- fixtures ------------------------------------------------------------

  private JsonNode payload(java.util.Map<String, Object> outboxRow) throws Exception {
    return mapper.readTree(String.valueOf(outboxRow.get("payload")));
  }

  private Order delivered() {
    Order order = deliveredOrder(outlet);
    deliver("receipt.on-delivery-completed", completed(order, Instant.now()), driver.id());
    return order;
  }

  private static String confirm(UUID orderId, Long version) {
    return envelope("receipt:Confirm", version, "{\"orderId\":\"" + orderId + "\"}");
  }

  private static String partial(UUID orderId, Long version, String lines) {
    return envelope(
        "receipt:ConfirmPartial", version,
        "{\"orderId\":\"" + orderId + "\",\"lines\":" + lines + ",\"note\":\"wet cartons\"}");
  }

  private static String dispute(UUID orderId, Long version, String reason, String lines) {
    return envelope(
        "receipt:Dispute", version,
        "{\"orderId\":\"" + orderId + "\",\"reason\":\"" + reason + "\",\"lines\":" + lines + "}");
  }
}
