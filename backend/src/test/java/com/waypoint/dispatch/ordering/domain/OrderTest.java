package com.waypoint.dispatch.ordering.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

  private static final LocalDate DAY = LocalDate.parse("2027-03-03");
  private static final DeliveryDate ON_TIME = new DeliveryDate(DAY, DAY, List.of());
  private static final List<OrderLine> LINES = List.of(new OrderLine("P-100", 4));

  private static Reservation reservation(String temperature) {
    return new Reservation("WH-1", new BigDecimal("82.400"), new BigDecimal("0.443"), temperature, 11);
  }

  private static Order order(Optional<Reservation> reservation) {
    return Order.place(
        UUID.randomUUID(), "WPO-TEST", "OUT001", "Peliyagoda", "Fresh", "Colombo", ON_TIME,
        reservation, LINES);
  }

  @Test
  void anOrderCarriedIntoALaterRunTravelsOnThatRunsDayAndNeverMovesEarlier() {
    // R-ORD-16: never planned on its own day, it goes with the next run.
    Order carried = order(Optional.of(reservation("ambient"))).allocateTo(UUID.randomUUID(), DAY.plusDays(1));
    assertEquals(OrderStatus.ALLOCATED, carried.status());
    assertEquals(DAY.plusDays(1), carried.deliveryDate());
    assertEquals(DAY, order(Optional.of(reservation("ambient"))).allocateTo(UUID.randomUUID(), DAY).deliveryDate());
    assertEquals(DAY, order(Optional.of(reservation("ambient"))).allocateTo(UUID.randomUUID(), DAY.minusDays(1)).deliveryDate());
  }

  @Test
  void aReservedPlacementIsConfirmedAndAnUnreachableWarehouseLeavesItStockUnknown() {
    assertEquals(OrderStatus.CONFIRMED, order(Optional.of(reservation("ambient"))).status());
    assertEquals(OrderStatus.STOCK_UNKNOWN, order(Optional.empty()).status());
  }

  @Test
  void aNonPositiveMeasureIsRejectedWithTheField() {
    DomainException zero =
        assertThrows(
            DomainException.class,
            () -> new Reservation("WH-1", BigDecimal.ZERO, new BigDecimal("0.4"), "ambient", 3));
    assertEquals(ErrorCode.VALIDATION_FAILED, zero.code());
    assertTrue(zero.getMessage().startsWith("weightKg"), zero.getMessage());

    DomainException negative =
        assertThrows(
            DomainException.class,
            () -> new Reservation("WH-1", BigDecimal.TEN, new BigDecimal("-0.1"), "ambient", 3));
    assertTrue(negative.getMessage().startsWith("volumeM3"), negative.getMessage());

    DomainException quantity =
        assertThrows(DomainException.class, () -> new OrderLine("P-1", 0));
    assertTrue(quantity.getMessage().contains("quantity"), quantity.getMessage());
  }

  @Test
  void anOrderHasExactlyOneTemperatureClass() {
    DomainException mixed =
        assertThrows(DomainException.class, () -> reservation("mixed"));
    assertEquals(List.of("R-ORD-06"), mixed.rules());

    Optional<String> violation =
        TemperatureMix.violation(
            Map.of("P-1", Optional.of("chilled"), "P-2", Optional.of("ambient")));
    assertTrue(violation.isPresent());
    assertTrue(
        TemperatureMix.violation(Map.of("P-1", Optional.of("chilled"), "P-2", Optional.empty()))
            .isEmpty(),
        "an unpublished temperature is not evidence of a mix");
  }

  @Test
  void aDryAndAChilledOrderForTheSameOutletAndDayStayTwoOrders() {
    Order dry = order(Optional.of(reservation("ambient")));
    Order chilled = order(Optional.of(reservation("chilled")));

    assertEquals(dry.outletId(), chilled.outletId());
    assertEquals(dry.deliveryDate(), chilled.deliveryDate());
    assertNotEquals(dry.orderId(), chilled.orderId());
  }

  @Test
  void theOrderTotalWinsOverItsLines() {
    Order confirmed = order(Optional.of(reservation("ambient")));
    BigDecimal linesSayHeavier = new BigDecimal("90.000");

    Optional<BigDecimal> gap =
        LineDiscrepancy.relative(confirmed.reservation().orElseThrow().weightKg(), linesSayHeavier);

    assertTrue(gap.orElseThrow().compareTo(new BigDecimal("0.09")) > 0);
    assertEquals(new BigDecimal("82.400"), confirmed.reservation().orElseThrow().weightKg());
  }

  @Test
  void anOrderTheWarehouseSuppliedWithoutLinesIsNormal() {
    Order original = order(Optional.of(reservation("ambient")));
    Order supplied =
        new Order(
            UUID.randomUUID(), "ORD0091466", "OUT001", "Peliyagoda", "Fresh", "Colombo", DAY, DAY,
            DAY, OrderStatus.CONFIRMED, original.reservation(), Optional.empty(), Optional.empty(),
            0, List.of(), 0);

    assertTrue(supplied.lines().isEmpty());
    assertThrows(DomainException.class, () -> OrderLine.forSubmission(List.of()));
  }

  @Test
  void amendingAfterAllocationIsAConflictAndAfterLoadingARejection() {
    Order allocated = order(Optional.of(reservation("ambient"))).allocateTo(UUID.randomUUID());
    DomainException conflict =
        assertThrows(DomainException.class, () -> allocated.amend(LINES, Optional.empty()));
    assertEquals(ErrorCode.CONFLICT, conflict.code());
    assertEquals(List.of("ORD-05"), conflict.rules());

    Order loading = allocated.moveTo(OrderStatus.LOADING);
    DomainException refused =
        assertThrows(DomainException.class, () -> loading.amend(LINES, Optional.empty()));
    assertEquals(ErrorCode.CONSTRAINT_VIOLATED, refused.code());
    assertEquals(List.of("ORD-06"), refused.rules());
  }

  @Test
  void amendingAStockUnknownOrderThatNowReservesConfirmsIt() {
    Order amended =
        order(Optional.empty()).amend(List.of(new OrderLine("P-9", 1)), Optional.of(reservation("chilled")));

    assertEquals(OrderStatus.CONFIRMED, amended.status());
    assertEquals("P-9", amended.lines().get(0).productId());
  }

  @Test
  void cancellingALoadedOrderIsRefusedWithTheReason() {
    Order loading =
        order(Optional.of(reservation("ambient"))).allocateTo(UUID.randomUUID()).moveTo(OrderStatus.LOADING);

    DomainException refused = assertThrows(DomainException.class, loading::cancel);
    assertEquals(List.of("ORD-10"), refused.rules());
    assertEquals(OrderStatus.CANCELLED, order(Optional.empty()).cancel().status());
  }

  @Test
  void aDeferralKeepsTheReservationAndCountsTheSkip() {
    Order deferred =
        order(Optional.of(reservation("ambient"))).deferTo(LocalDate.parse("2027-03-04"), 1);

    assertEquals(OrderStatus.DEFERRED, deferred.status());
    assertTrue(deferred.reservation().isPresent(), "deferral never cancels stock (D-H)");
    assertEquals(1, deferred.deferralCount());
    assertEquals(DAY, deferred.originalRequestedDate());
  }

  @Test
  void aRedeliveryCarriesTheOriginalReservationAndLink() {
    Order original = order(Optional.of(reservation("chilled")));
    Order redelivery =
        Order.redeliveryOf(original, UUID.randomUUID(), "WPO-REDO", DAY.plusDays(2), DAY.plusDays(2));

    assertEquals(Optional.of(original.orderId()), redelivery.redeliveryOf());
    assertEquals(original.reservation(), redelivery.reservation());
    assertEquals(OrderStatus.CONFIRMED, redelivery.status());
    assertEquals(
        original.deferralCount() + 1, redelivery.deferralCount(),
        "the failed delivery skipped the outlet, so the next plan serves it first (R-PLN-20)");
  }

  @Test
  void anOrderRefIsStablePerCommandAndDistinctAcrossCommands() {
    UUID actor = UUID.randomUUID();
    UUID command = UUID.randomUUID();

    String ref = OrderRef.derive(actor, command);

    assertEquals(ref, OrderRef.derive(actor, command));
    assertNotEquals(ref, OrderRef.derive(actor, UUID.randomUUID()));
    assertNotEquals(ref, OrderRef.derive(UUID.randomUUID(), command));
    assertTrue(ref.matches("^WPO-[0-9A-HJKMNP-TV-Z]{12}$"), ref);
  }

  @Test
  void aWindowShorterThanTheAllowanceIsRejectedWithTheArithmetic() {
    Optional<String> tooShort =
        WindowFeasibility.violation(
            Optional.of(LocalTime.of(5, 0)), Optional.of(LocalTime.of(5, 20)), new BigDecimal("25"));

    assertTrue(tooShort.orElseThrow().contains("20 min"), tooShort.get());
    assertTrue(tooShort.get().contains("25 min"), tooShort.get());
    assertTrue(
        WindowFeasibility.violation(
                Optional.of(LocalTime.of(5, 0)), Optional.of(LocalTime.of(7, 30)), new BigDecimal("25"))
            .isEmpty());
    assertTrue(
        WindowFeasibility.violation(Optional.empty(), Optional.empty(), BigDecimal.ONE).isPresent());
  }
}
