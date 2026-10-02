package com.waypoint.dispatch.receipt.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptAutoClosed;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptDisputed;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository.Stored;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A recorded delivery opening a receipt, and silence closing it, through a
 * stand-in for the relay and the scheduler (#6): consumers are called the way
 * the relay will call them, and the job is run at chosen instants.
 */
class ReceiptConsumersIntegrationTest extends ReceiptIssuesSupport {
  @Autowired ReceiptAutoCloseJob autoClose;
  @Autowired JdbcReceiptRepository receipts;

  @Test
  void aRedeliveredEventOpensOneReceiptWithTheOrdersLinesAndTheDriverNamed() {
    Order order = deliveredOrder(outlet);
    DeliveryCompleted event = completed(order, Instant.now());

    UUID eventId = deliver("receipt.on-delivery-completed", event, driver.id());
    redeliver("receipt.on-delivery-completed", eventId, event, Optional.of(driver.id()));
    deliver("receipt.on-delivery-completed", event, driver.id());

    Stored stored = stored(order);
    assertEquals(2, stored.receipt().lines().size(), "lines come from the order (D-E)");
    assertEquals(order.depotCode(), stored.receipt().depotCode());
    assertEquals(Optional.of(driver.id()), stored.deliveredBy(), "R-RCP-08: who delivered it");
    assertEquals(
        1L,
        ((Number) database.asSystem(ModuleRole.RECEIPT, () -> database.queryOne(
            "SELECT count(*) AS n FROM receipt.confirmations WHERE order_id = ?", order.orderId()).get("n")))
            .longValue());
  }

  @Test
  void silenceClosesAtTheDeadlineAndARerunChangesNothing() {
    Instant delivered = Instant.parse("2026-03-02T04:00:00Z").plusSeconds(java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 3600));
    Order order = deliveredOrder(outlet);
    deliver("receipt.on-delivery-completed", completed(order, delivered), driver.id());
    Instant deadline = delivered.plus(Duration.ofHours(24));

    autoClose.runAt(deadline.minusSeconds(1));
    assertEquals("PENDING", status(order));

    autoClose.runAt(deadline);
    assertEquals("AUTO_CLOSED", status(order), "RCP-02: never silently received");
    String receiptId = stored(order).receipt().receiptId().toString();
    assertEquals(1, outbox(receiptId, "receipt.auto_closed").size());

    long version = stored(order).receipt().rowVersion();
    autoClose.runAt(deadline.plus(Duration.ofHours(1)));
    assertEquals(version, stored(order).receipt().rowVersion(), "a re-run is a no-op");
    assertEquals(1, outbox(receiptId, "receipt.auto_closed").size());
  }

  @Test
  void aShortageAfterAutoCloseIsAcceptedLateAndNeverReopensTheOrder() throws Exception {
    Instant delivered = Instant.parse("2026-03-05T04:00:00Z").plusSeconds(java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 3600));
    Order order = deliveredOrder(outlet);
    deliver("receipt.on-delivery-completed", completed(order, delivered), driver.id());
    autoClose.runAt(delivered.plus(Duration.ofHours(24)));
    Stored closed = stored(order);
    deliver(
        "ordering.on-receipt-auto-closed",
        new ReceiptAutoClosed(closed.receipt().receiptId(), order.orderId(), order.outletId(), Instant.now()));
    assertEquals(OrderStatus.UNCONFIRMED, orderStatus(order.orderId()));

    JsonNode late =
        send(manager,
            envelope("receipt:ConfirmPartial", closed.receipt().rowVersion(),
                "{\"orderId\":\"" + order.orderId() + "\",\"lines\":[{\"productId\":\"P-1\",\"receivedQuantity\":8}]}"),
            200).get("result");

    assertEquals("PARTIAL", late.get("status").asText());
    assertTrue(late.get("late").asBoolean(), "RCP-08: accepted and linked");
    String receiptId = late.get("receiptId").asText();
    assertEquals(0, outbox(receiptId, "receipt.confirmed").size(), "UNCONFIRMED is terminal in Ordering");
    assertEquals(1, outbox(receiptId, "receipt.disputed").size(), "Issues investigates it");

    deliver(
        "ordering.on-receipt-disputed",
        new ReceiptDisputed(UUID.fromString(receiptId), order.orderId(), order.outletId(), depot, "late", Instant.now()));
    assertEquals(OrderStatus.UNCONFIRMED, orderStatus(order.orderId()), "a late dispute does not reopen the order");
  }

  @Test
  void aDisputedReceiptNeverAutoCloses() throws Exception {
    Instant delivered = Instant.parse("2026-03-08T04:00:00Z").plusSeconds(java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 3600));
    Order order = deliveredOrder(outlet);
    deliver("receipt.on-delivery-completed", completed(order, delivered), driver.id());
    send(manager,
        envelope("receipt:Dispute", 1L,
            "{\"orderId\":\"" + order.orderId() + "\",\"reason\":\"wrong products\",\"lines\":[]}"),
        200);

    autoClose.runAt(delivered.plus(Duration.ofDays(30)));

    assertEquals("DISPUTED", status(order));
  }

  private Stored stored(Order order) {
    return database.asSystem(ModuleRole.RECEIPT, () -> receipts.findByOrder(order.orderId()).orElseThrow());
  }

  private String status(Order order) {
    return stored(order).receipt().status().name();
  }
}
