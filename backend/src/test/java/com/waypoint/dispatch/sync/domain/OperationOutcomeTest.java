package com.waypoint.dispatch.sync.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.sync.contract.SyncViews.OperationStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OperationOutcomeTest {

  @Test
  void aStaleVersionIsHeldAsAConflictNeverMerged() {
    assertEquals(
        Optional.of(OperationStatus.CONFLICT),
        OperationOutcome.forFailure(ErrorCode.VERSION_CONFLICT));
  }

  @Test
  void aRuleRefusalIsRejectedBecauseResendingCannotHelp() {
    for (ErrorCode code :
        new ErrorCode[] {
          ErrorCode.VALIDATION_FAILED,
          ErrorCode.NOT_FOUND,
          ErrorCode.CONFLICT,
          ErrorCode.FORBIDDEN,
          ErrorCode.CONSTRAINT_VIOLATED
        }) {
      assertEquals(Optional.of(OperationStatus.REJECTED), OperationOutcome.forFailure(code), code.name());
    }
  }

  @Test
  void anOutageLeavesTheOperationInFlightSoTheBatchStops() {
    assertTrue(OperationOutcome.forFailure(ErrorCode.DEPENDENCY_UNAVAILABLE).isEmpty());
    assertTrue(OperationOutcome.forFailure(ErrorCode.UNAUTHENTICATED).isEmpty());
  }

  @Test
  void onlyASettledOperationCanBeAcknowledged() {
    assertFalse(OperationOutcome.canAcknowledge(OperationStatus.RECEIVED));
    for (OperationStatus status : OperationStatus.values()) {
      if (status != OperationStatus.RECEIVED) {
        assertTrue(OperationOutcome.canAcknowledge(status), status.name());
      }
    }
  }
}
