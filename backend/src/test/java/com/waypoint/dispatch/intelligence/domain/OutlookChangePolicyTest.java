package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.waypoint.dispatch.intelligence.domain.DateOutlookPolicy.Status;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #224, R-ML-08: a store is warned once, and only when its booked day gets worse. */
class OutlookChangePolicyTest {
  @Test
  void aDayThatTurnsBusyOrAtRiskIsWarnedOf() {
    assertEquals(Optional.of(Status.BUSY), OutlookChangePolicy.warn(Optional.empty(), Status.BUSY));
    assertEquals(Optional.of(Status.AT_RISK), OutlookChangePolicy.warn(Optional.empty(), Status.AT_RISK));
  }

  @Test
  void aCalmOrUnknownDayIsNeverAWarning() {
    for (Status calm : new Status[] {Status.ON_TRACK, Status.TOO_EARLY, Status.CLOSED}) {
      assertEquals(Optional.empty(), OutlookChangePolicy.warn(Optional.empty(), calm), calm.name());
    }
  }

  @Test
  void busyThenAtRiskWarnsAgainButNeverTheSameOrMilder() {
    assertEquals(Optional.of(Status.AT_RISK), OutlookChangePolicy.warn(Optional.of(Status.BUSY), Status.AT_RISK));
    assertEquals(Optional.empty(), OutlookChangePolicy.warn(Optional.of(Status.BUSY), Status.BUSY));
    assertEquals(Optional.empty(), OutlookChangePolicy.warn(Optional.of(Status.AT_RISK), Status.BUSY));
  }

  @Test
  void aDayThatEasesAndWorsensAgainIsNotToldTwice() {
    Optional<Status> warned = Optional.of(Status.BUSY);
    assertEquals(Optional.empty(), OutlookChangePolicy.warn(warned, Status.ON_TRACK));
    assertEquals(Optional.empty(), OutlookChangePolicy.warn(warned, Status.BUSY), "no flip-flop");
  }
}
