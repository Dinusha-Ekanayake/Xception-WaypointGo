package com.waypoint.dispatch.receipt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.receipt.application.ReceiptDataQuery;
import com.waypoint.dispatch.receipt.contract.ReceiptQuery;
import com.waypoint.dispatch.receipt.domain.Receipt;
import com.waypoint.dispatch.receipt.domain.ReceiptLine;
import com.waypoint.dispatch.receipt.infrastructure.JdbcReceiptRepository;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.support.ReceiptIssuesSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The receipt schema against a real PostgreSQL: row-level security decides who
 * sees which receipt, the version guard refuses a stale write, nothing is ever
 * deleted, and the window comes from an effective-dated parameter.
 */
class ReceiptSchemaIntegrationTest extends ReceiptIssuesSupport {
  @Autowired JdbcReceiptRepository receipts;
  @Autowired ReceiptDataQuery query;
  @Autowired ReceiptQuery contract;

  @Test
  void theOutletAndItsDepotSeeTheReceiptAndNoOneElseDoes() throws Exception {
    Receipt r = stored(deliveredOrder(outlet));

    assertEquals(r.receiptId().toString(), read(manager, "/api/receipts/" + r.orderId(), 200).get("receiptId").asText());
    assertEquals(r.receiptId().toString(), read(dispatcher, "/api/receipts/" + r.orderId(), 200).get("receiptId").asText());
    read(stranger, "/api/receipts/" + r.orderId(), 404);
    read(farDispatcher, "/api/receipts/" + r.orderId(), 404);
    assertTrue(contract.receiptFor(r.orderId()).isEmpty(), "a contract read with no actor sees nothing");
  }

  @Test
  void anotherOutletsPendingListIsForbiddenAndAudited() throws Exception {
    Receipt r = stored(deliveredOrder(outlet));

    JsonNode pending = read(manager, "/api/receipts/pending?outlet=" + outlet.outletId(), 200);
    assertTrue(pending.toString().contains(r.orderId().toString()));

    long before = denials(stranger.id(), ReceiptDataQuery.READ);
    read(stranger, "/api/receipts/pending?outlet=" + outlet.outletId(), 403);
    assertEquals(before + 1, denials(stranger.id(), ReceiptDataQuery.READ), "RCP-05: 403 plus audit, never empty");
  }

  @Test
  void aStaleVersionIsRefusedAndNothingIsEverDeleted() {
    Receipt r = stored(deliveredOrder(outlet));
    Receipt confirmed = r.confirm(manager.id(), Instant.now());
    database.asSystem(ModuleRole.RECEIPT, () -> receipts.update(confirmed, 1, Instant.now()));

    DomainException stale =
        assertThrows(
            DomainException.class,
            () -> database.asSystem(ModuleRole.RECEIPT, () -> receipts.update(confirmed, 1, Instant.now())));
    assertEquals(ErrorCode.VERSION_CONFLICT, stale.code());
    assertThrows(
        RuntimeException.class,
        () -> database.asSystem(
            ModuleRole.RECEIPT,
            () -> database.update("DELETE FROM receipt.confirmations WHERE receipt_id = ?", r.receiptId())));
  }

  @Test
  void theWindowComesFromTheParameterInForce() {
    var parameters = database.asSystem(ModuleRole.RECEIPT, () -> receipts.parameters(LocalDate.now()));
    assertEquals(Duration.ofHours(24), parameters.autoClose().window(), "P-10");
    assertThrows(
        RuntimeException.class,
        () -> database.asSystem(
            ModuleRole.RECEIPT,
            () -> database.update("UPDATE receipt.parameters SET parameter_value = 1")),
        "a parameter changes by a new row from a migration, never at runtime");
  }

  @Test
  void theCustodyChainNamesWhatIsNotDeployedRatherThanShowingItEmpty() throws Exception {
    Receipt r = stored(deliveredOrder(outlet));

    JsonNode custody = read(dispatcher, "/api/receipts/" + r.orderId() + "/custody", 200);

    assertEquals(r.deliveryId().toString(), custody.get("delivery").get("deliveryId").asText());
    assertEquals(1, custody.get("unavailable").size(), custody.toString());
    assertTrue(custody.get("loadingCheck").isNull());
    assertTrue(custody.get("proof").isNull());
  }

  private Receipt stored(Order order) {
    Instant delivered = Instant.now();
    Receipt r =
        Receipt.pending(
            UUID.randomUUID(), order.orderId(), UUID.randomUUID(), UUID.randomUUID(), order.outletId(),
            order.depotCode(), List.of(ReceiptLine.expected("P-1", 10), ReceiptLine.expected("P-2", 4)), delivered,
            new com.waypoint.dispatch.receipt.domain.AutoClosePolicy(Duration.ofHours(24)));
    database.asSystem(
        ModuleRole.RECEIPT, () -> receipts.insert(r, Optional.of(driver.id()), Optional.of(14), delivered));
    return r;
  }
}
