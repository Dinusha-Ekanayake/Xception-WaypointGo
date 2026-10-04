package com.waypoint.dispatch.execution.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.execution.domain.DeliveryLines.Delivered;
import com.waypoint.dispatch.execution.domain.DeliveryLines.Ordered;
import com.waypoint.dispatch.shared.error.DomainException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A partial delivery recorded product by product (decision 2026-10-01, issue #12). */
class DeliveryLinesTest {
  private static final List<Ordered> ORDER = List.of(new Ordered("P-1", 30), new Ordered("P-2", 10));

  private static DomainException refused(Runnable action) {
    return assertThrows(DomainException.class, action::run);
  }

  @Test
  void aPartialDeliveryNamesWhatArrivedOfEachProductAndTheTotalFollows() {
    var settled = DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 30), new Delivered("P-2", 4)), Optional.empty());

    assertEquals(34, settled.deliveredUnits().orElseThrow());
    assertEquals(List.of(new Delivered("P-1", 30), new Delivered("P-2", 4)), settled.lines());
  }

  @Test
  void aFullDeliveryWithLinesMustHaveEveryUnitOfEveryProduct() {
    var settled = DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.DELIVERED,
        List.of(new Delivered("P-1", 30), new Delivered("P-2", 10)), Optional.empty());
    assertEquals(40, settled.deliveredUnits().orElseThrow());

    refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.DELIVERED,
        List.of(new Delivered("P-1", 30), new Delivered("P-2", 9)), Optional.empty()));
  }

  @Test
  void aPartialDeliveryHasAShortLineAndSomethingDelivered() {
    refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 30), new Delivered("P-2", 10)), Optional.empty()));
    refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 0), new Delivered("P-2", 0)), Optional.empty()));
  }

  @Test
  void everyProductIsAccountedForOnceAndNeverOverDelivered() {
    refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL, List.of(new Delivered("P-1", 3)), Optional.empty()));
    refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 3), new Delivered("P-1", 3), new Delivered("P-2", 1)), Optional.empty()));
    refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 3), new Delivered("P-9", 1)), Optional.empty()));
    refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 31), new Delivered("P-2", 1)), Optional.empty()));
    refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", -1), new Delivered("P-2", 1)), Optional.empty()));
  }

  @Test
  void aTotalThatContradictsTheLinesIsRefused() {
    DomainException e = refused(() -> DeliveryLines.settle(
        ORDER, 40, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 30), new Delivered("P-2", 4)), Optional.of(33)));
    assertTrue(e.getMessage().contains("34"));
  }

  @Test
  void whenTheLinesDoNotAddUpToTheOrdersUnitCountTheDriversTotalIsStillNeeded() {
    // The unit count is the warehouse's; product lines are descriptive and may differ (D-E).
    List<Ordered> order = List.of(new Ordered("P-1", 30), new Ordered("P-2", 10));
    var settled = DeliveryLines.settle(
        order, 44, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 30), new Delivered("P-2", 4)), Optional.of(38));
    assertEquals(38, settled.deliveredUnits().orElseThrow());

    refused(() -> DeliveryLines.settle(
        order, 44, DeliveryOutcome.PARTIAL,
        List.of(new Delivered("P-1", 30), new Delivered("P-2", 4)), Optional.empty()));
  }

  @Test
  void withoutLinesTheTotalAloneIsRecordedAsBefore() {
    var settled = DeliveryLines.settle(ORDER, 40, DeliveryOutcome.PARTIAL, List.of(), Optional.of(12));
    assertEquals(12, settled.deliveredUnits().orElseThrow());
    assertTrue(settled.lines().isEmpty());
  }

  @Test
  void linesNeedAnOrderThatHasThem() {
    refused(() -> DeliveryLines.settle(
        List.of(), 40, DeliveryOutcome.PARTIAL, List.of(new Delivered("P-1", 3)), Optional.empty()));
  }
}
