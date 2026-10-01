package com.waypoint.dispatch.receipt.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.receipt.contract.ReceiptCommands.ReceivedLine;
import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The store's answer to a delivery, with no database and the time passed in. */
class ReceiptTest {
  static final Instant DELIVERED = Instant.parse("2026-10-05T03:00:00Z");
  static final UUID STORE = UUID.randomUUID();
  static final AutoClosePolicy DAY = new AutoClosePolicy(Duration.ofHours(24));

  Receipt pending() {
    return Receipt.pending(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OUT001", "Peliyagoda",
        List.of(ReceiptLine.expected("P-1", 10), ReceiptLine.expected("P-2", 4)), DELIVERED, DAY);
  }

  @Test
  void aReceiptOpensPendingWithItsDeadlineStamped() {
    Receipt r = pending();
    assertEquals(ReceiptStatus.PENDING, r.status());
    assertEquals(DELIVERED.plus(Duration.ofHours(24)), r.closesAt(), "P-10 is stamped when the receipt opens");
    assertTrue(r.lines().stream().allMatch(l -> l.receivedQuantity().isEmpty()));
  }

  @Test
  void aConfirmationWithoutADeliveryIsRefused() {
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> Receipt.pending(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), "OUT001", "Peliyagoda",
                List.of(ReceiptLine.expected("P-1", 1)), DELIVERED, DAY));
    assertEquals(List.of("RCP-04"), refused.rules(), "a receipt exists only for a recorded delivery");
  }

  @Test
  void aConfirmationReceivesEveryLineInFull() {
    Receipt r = pending().confirm(STORE, DELIVERED.plusSeconds(600));
    assertEquals(ReceiptStatus.CONFIRMED, r.status());
    assertEquals(0, r.unitsShort());
    assertEquals(Optional.of(STORE), r.confirmedBy());
    assertFalse(r.late());
  }

  @Test
  void aPartialReceiptRecordsWhatIsShortAndUnnamedLinesArriveInFull() {
    Receipt r = pending().confirmPartial(List.of(new ReceivedLine("P-1", 7)), Optional.of(" two cartons wet "), STORE,
        DELIVERED.plusSeconds(600));

    assertEquals(ReceiptStatus.PARTIAL, r.status());
    assertEquals(3, r.unitsShort());
    assertEquals(Optional.of(4), r.lines().get(1).receivedQuantity(), "P-2 was not named, so it arrived in full");
    assertEquals(Optional.of("two cartons wet"), r.note());
  }

  @Test
  void aPartialWithNothingShortIsAConfirmation() {
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> pending().confirmPartial(List.of(new ReceivedLine("P-1", 10)), Optional.empty(), STORE, DELIVERED));
    assertEquals(ErrorCode.VALIDATION_FAILED, refused.code());
  }

  @Test
  void aPartialCannotReceiveMoreThanOrderedOrNameAProductNotOnTheOrder() {
    assertThrows(
        DomainException.class,
        () -> pending().confirmPartial(List.of(new ReceivedLine("P-1", 11)), Optional.empty(), STORE, DELIVERED));
    assertThrows(
        DomainException.class,
        () -> pending().confirmPartial(List.of(new ReceivedLine("P-9", 1)), Optional.empty(), STORE, DELIVERED));
    assertThrows(
        DomainException.class,
        () -> pending().confirmPartial(
            List.of(new ReceivedLine("P-1", 1), new ReceivedLine("P-1", 2)), Optional.empty(), STORE, DELIVERED));
  }

  @Test
  void aDisputeNeedsAReasonAndMayReportOverDelivery() {
    assertThrows(DomainException.class, () -> pending().dispute(" ", List.of(), STORE, DELIVERED));

    Receipt r = pending().dispute("extra cases we did not order", List.of(new ReceivedLine("P-2", 6)), STORE, DELIVERED);
    assertEquals(ReceiptStatus.DISPUTED, r.status());
    assertEquals(Optional.of(6), r.lines().get(1).receivedQuantity());
  }

  @Test
  void silenceAutoClosesAtTheWindowAndNotASecondBefore() {
    Receipt r = pending();
    Instant deadline = DELIVERED.plus(Duration.ofHours(24));
    assertFalse(r.isDue(deadline.minusSeconds(1)));
    assertThrows(DomainException.class, () -> r.autoClose(deadline.minusSeconds(1)));

    Receipt closed = r.autoClose(deadline);
    assertEquals(ReceiptStatus.AUTO_CLOSED, closed.status());
    assertTrue(closed.confirmedBy().isEmpty(), "R-RCP-05: never recorded as received by anyone");
    assertFalse(closed.isDue(deadline.plusSeconds(1)), "a closed receipt is not due again");
  }

  @Test
  void aShortageReportedAfterAutoCloseIsAcceptedAndMarkedLate() {
    Receipt closed = pending().autoClose(DELIVERED.plus(Duration.ofHours(24)));

    Receipt late = closed.confirmPartial(List.of(new ReceivedLine("P-1", 8)), Optional.empty(), STORE,
        DELIVERED.plus(Duration.ofHours(30)));

    assertEquals(ReceiptStatus.PARTIAL, late.status());
    assertTrue(late.late(), "RCP-08: auto-close is a state, not a deadline for the truth");
    assertThrows(
        DomainException.class,
        () -> closed.confirm(STORE, DELIVERED.plus(Duration.ofHours(30))),
        "after auto-close the store can report a problem, not confirm silence away");
  }

  @Test
  void anAnsweredReceiptCannotBeAnsweredAgainNorAutoClosed() {
    Receipt disputed = pending().dispute("seal broken", List.of(), STORE, DELIVERED);
    assertThrows(DomainException.class, () -> disputed.confirm(STORE, DELIVERED));
    assertFalse(disputed.isDue(DELIVERED.plus(Duration.ofDays(3))), "a disputed receipt never auto-closes");
  }

  @Test
  void theWindowComesFromParametersAndAMissingOneRefuses() {
    assertEquals(
        Duration.ofHours(24),
        new ReceiptParameters(Map.of(ReceiptParameters.AUTO_CLOSE_HOURS, new BigDecimal("24"))).autoClose().window());
    DomainException refused =
        assertThrows(DomainException.class, () -> new ReceiptParameters(Map.of()).autoClose());
    assertEquals(List.of("POL-10"), refused.rules());
  }
}
