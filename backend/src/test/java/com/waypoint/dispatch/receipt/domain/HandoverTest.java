package com.waypoint.dispatch.receipt.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.receipt.domain.Handover.Outcome;
import com.waypoint.dispatch.receipt.domain.Handover.Status;
import com.waypoint.dispatch.receipt.domain.Handover.Verification;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The handover PIN (R-RCP-09), with no database, no randomness and the time passed in. */
class HandoverTest {
  static final Instant NOW = Instant.parse("2026-10-05T05:40:00Z");
  static final UUID DRIVER = UUID.randomUUID();

  Handover issued(String pin) {
    return Handover.issue(
        UUID.randomUUID(), UUID.randomUUID(), "OUT001", "Peliyagoda", "VEH043", LocalDate.of(2026, 10, 5), pin,
        "salt-a", NOW);
  }

  @Test
  void aPinIsFourDigitsAndKeepsItsLeadingZeros() {
    assertEquals("0042", Handover.newPin(bound -> 42));
    assertEquals("9999", Handover.newPin(bound -> bound - 1));
    assertTrue(Handover.wellFormed("0042"));
    assertFalse(Handover.wellFormed("42"));
    assertFalse(Handover.wellFormed("12345"));
    assertFalse(Handover.wellFormed("12a4"));
    assertFalse(Handover.wellFormed(null));
  }

  @Test
  void onlyASaltedHashIsKept() {
    Handover h = issued("4827");
    assertFalse(h.toString().contains("4827"), "the PIN is not part of what is held");
    assertNotEquals(issued("4827").pinHash(), Handover.issue(
        h.receiptId(), h.orderId(), "OUT001", "Peliyagoda", "VEH043", h.serviceDate(), "4827", "salt-b", NOW).pinHash(),
        "the salt changes the hash");
    assertEquals(Status.AWAITING, h.status(NOW));
    assertEquals(NOW.plus(Handover.LIFETIME), h.expiresAt());
  }

  @Test
  void theRightPinConfirmsAndNamesTheDriver() {
    Verification v = issued("4827").verify("4827", DRIVER, NOW.plusSeconds(60));
    assertEquals(Outcome.VERIFIED, v.outcome());
    assertEquals(Handover.State.CONFIRMED, v.next().state());
    assertEquals(DRIVER, v.next().confirmedBy().orElseThrow());
    assertEquals(NOW.plusSeconds(60), v.next().confirmedAt().orElseThrow());
  }

  @Test
  void aWrongPinIsCountedAndTheFifthLocksIt() {
    Handover h = issued("4827");
    for (int wrong = 1; wrong < Handover.MAX_ATTEMPTS; wrong++) {
      Verification v = h.verify("0000", DRIVER, NOW);
      assertEquals(Outcome.WRONG, v.outcome());
      assertEquals(wrong, v.next().attempts());
      assertEquals(Handover.MAX_ATTEMPTS - wrong, v.attemptsLeft());
      h = v.next();
    }
    Verification last = h.verify("0000", DRIVER, NOW);
    assertEquals(Outcome.LOCKED, last.outcome());
    assertEquals(Status.LOCKED, last.next().status(NOW));
    assertEquals(0, last.attemptsLeft());
  }

  @Test
  void aLockedPinStaysLockedEvenForTheRightEntry() {
    Handover h = issued("4827");
    for (int i = 0; i < Handover.MAX_ATTEMPTS; i++) {
      h = h.verify("0000", DRIVER, NOW).next();
    }
    Verification v = h.verify("4827", DRIVER, NOW);
    assertEquals(Outcome.LOCKED, v.outcome(), "guessing then knowing does not get round the limit");
    assertEquals(Handover.State.LOCKED, v.next().state());
  }

  @Test
  void aPinPastItsTimeIsExpiredAndTheEntryChangesNothing() {
    Handover h = issued("4827");
    Instant late = h.expiresAt();
    assertEquals(Status.EXPIRED, h.status(late));
    Verification v = h.verify("4827", DRIVER, late);
    assertEquals(Outcome.EXPIRED, v.outcome());
    assertEquals(h, v.next());
    assertEquals(Outcome.VERIFIED, h.verify("4827", DRIVER, late.minusSeconds(1)).outcome(), "one second earlier it was good");
  }

  @Test
  void enteringAConfirmedPinAgainChangesNothing() {
    Handover confirmed = issued("4827").verify("4827", DRIVER, NOW).next();
    Verification again = confirmed.verify("0000", UUID.randomUUID(), NOW.plusSeconds(30));
    assertEquals(Outcome.ALREADY_CONFIRMED, again.outcome());
    assertEquals(confirmed, again.next(), "neither the time nor the confirming driver is rewritten");
  }

  @Test
  void aMalformedEntryIsRefusedAndCountsForNothing() {
    Handover h = issued("4827");
    DomainException e = assertThrows(DomainException.class, () -> h.verify("48", DRIVER, NOW));
    assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
    assertEquals(0, h.attempts());
  }

  @Test
  void aNewPinStartsAgainAfterALockOrAnExpiry() {
    Handover locked = issued("4827");
    for (int i = 0; i < Handover.MAX_ATTEMPTS; i++) {
      locked = locked.verify("0000", DRIVER, NOW).next();
    }
    Instant later = NOW.plusSeconds(3600);
    Handover fresh = locked.reissue("1111", "salt-c", later);
    assertEquals(Handover.State.AWAITING, fresh.state());
    assertEquals(0, fresh.attempts());
    assertEquals(later.plus(Handover.LIFETIME), fresh.expiresAt());
    assertEquals(Outcome.WRONG, fresh.verify("4827", DRIVER, later).outcome(), "the old PIN no longer works");
    assertEquals(Outcome.VERIFIED, fresh.verify("1111", DRIVER, later).outcome());
  }

  @Test
  void aConfirmedHandoverIsFinal() {
    Handover confirmed = issued("4827").verify("4827", DRIVER, NOW).next();
    DomainException e = assertThrows(DomainException.class, () -> confirmed.reissue("1111", "salt-c", NOW));
    assertEquals(ErrorCode.CONFLICT, e.code());
  }
}
