package com.waypoint.dispatch.issues.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.DomainException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FF-03, P-DG-1: who may approve a write-off, with no database. */
class WriteOffRulesTest {
  static final BigDecimal LIMIT = new BigDecimal("25000");
  final UUID recorder = UUID.randomUUID();
  final UUID approver = UUID.randomUUID();
  final UUID resolver = UUID.randomUUID();

  @Test
  void aboveTheLimitOrATechLineNeedsTheHeadOfDistribution() {
    assertFalse(WriteOffRules.needsHead(new BigDecimal("25000"), false, LIMIT));
    assertTrue(WriteOffRules.needsHead(new BigDecimal("25000.01"), false, LIMIT));
    assertTrue(WriteOffRules.needsHead(new BigDecimal("10"), true, LIMIT));
  }

  @Test
  void theRecorderAndTheIssuesDeciderMayNotApprove() {
    assertThrows(DomainException.class, () -> decide(recorder, Optional.empty(), true, "100", false, false));
    assertThrows(DomainException.class, () -> decide(resolver, Optional.of(resolver), true, "100", false, false));
    assertDoesNotThrow(() -> decide(approver, Optional.of(resolver), true, "100", false, false));
  }

  @Test
  void aManagerApprovesUpToTheLimitAndRejectsAnything() {
    assertThrows(DomainException.class, () -> decide(approver, Optional.empty(), true, "30000", false, false));
    assertThrows(DomainException.class, () -> decide(approver, Optional.empty(), true, "5", true, false));
    assertDoesNotThrow(() -> decide(approver, Optional.empty(), true, "30000", false, true));
    assertDoesNotThrow(() -> decide(approver, Optional.empty(), false, "30000", true, false), "a rejection is never limited");
  }

  @Test
  void anInquiryNeedsWordsAndAValueInRupees() {
    assertThrows(DomainException.class, () -> WriteOffRules.requireOutcome(" a "));
    assertThrows(DomainException.class, () -> WriteOffRules.requireValue(new BigDecimal("-1")));
    assertThrows(DomainException.class, () -> WriteOffRules.requireValue(new BigDecimal("1.234")));
    assertThrows(DomainException.class, () -> WriteOffRules.requireValue(new BigDecimal("10000001")));
    assertDoesNotThrow(() -> WriteOffRules.requireValue(new BigDecimal("4999.50")));
  }

  private void decide(UUID who, Optional<UUID> decider, boolean approving, String value, boolean tech, boolean head) {
    WriteOffRules.requireMayDecide(who, recorder, decider, approving, new BigDecimal(value), tech, LIMIT, head);
  }
}
