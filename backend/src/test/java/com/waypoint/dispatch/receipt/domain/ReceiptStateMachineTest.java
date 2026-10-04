package com.waypoint.dispatch.receipt.domain;

import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.AUTO_CLOSED;
import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.CONFIRMED;
import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.DISPUTED;
import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.PARTIAL;
import static com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus.PENDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.receipt.contract.ReceiptViews.ReceiptStatus;
import com.waypoint.dispatch.shared.error.DomainException;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ReceiptStateMachineTest {

  @Test
  void pendingTakesExactlyOneAnswerOrAutoCloses() {
    assertEquals(EnumSet.of(CONFIRMED, PARTIAL, DISPUTED, AUTO_CLOSED), ReceiptStateMachine.next(PENDING));
  }

  @Test
  void autoClosedStillAcceptsAShortageButNeverAConfirmation() {
    assertTrue(ReceiptStateMachine.isEdge(AUTO_CLOSED, PARTIAL));
    assertTrue(ReceiptStateMachine.isEdge(AUTO_CLOSED, DISPUTED));
    assertFalse(ReceiptStateMachine.isEdge(AUTO_CLOSED, CONFIRMED));
  }

  @Test
  void answeredStatesAreTerminal() {
    for (ReceiptStatus s : ReceiptStateMachine.TERMINAL) {
      assertTrue(ReceiptStateMachine.next(s).isEmpty(), s + " is terminal");
    }
    assertThrows(DomainException.class, () -> ReceiptStateMachine.require(DISPUTED, AUTO_CLOSED));
  }
}
