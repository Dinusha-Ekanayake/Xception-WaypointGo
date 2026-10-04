package com.waypoint.dispatch.execution.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

class StoreAnswerWaiverTest {

  @Test
  void theReasonsAreAClosedListReadInAnyCase() {
    assertEquals(StoreAnswerWaiver.Reason.STORE_ABSENT, StoreAnswerWaiver.Reason.parse("store_absent"));
    assertEquals(StoreAnswerWaiver.Reason.NO_SIGNAL, StoreAnswerWaiver.Reason.parse(" NO_SIGNAL "));
    assertEquals("disagree", StoreAnswerWaiver.Reason.DISAGREE.code());
    DomainException wrong = assertThrows(DomainException.class, () -> StoreAnswerWaiver.Reason.parse("tired"));
    assertEquals(ErrorCode.VALIDATION_FAILED, wrong.code());
    assertThrows(DomainException.class, () -> StoreAnswerWaiver.Reason.parse(null));
  }

  @Test
  void onlyAHandedOverStopWaitsForTheStore() {
    StoreAnswerWaiver.requireHandedOver(DeliveryOutcome.DELIVERED);
    StoreAnswerWaiver.requireHandedOver(DeliveryOutcome.PARTIAL);
    for (DeliveryOutcome none :
        new DeliveryOutcome[] {DeliveryOutcome.PENDING, DeliveryOutcome.ARRIVED, DeliveryOutcome.FAILED, DeliveryOutcome.SKIPPED}) {
      DomainException refused = assertThrows(DomainException.class, () -> StoreAnswerWaiver.requireHandedOver(none));
      assertEquals(ErrorCode.CONSTRAINT_VIOLATED, refused.code(), none.name());
    }
  }
}
