package com.waypoint.dispatch.ordering.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.ordering.domain.RideAlong.Suggestion;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R-ORD-13: the "a trip already goes there" hint, with days and bookings as plain values. */
class RideAlongTest {
  /** Monday 1 March 2027 and the days around it. */
  private static final LocalDate SAT = LocalDate.of(2027, 2, 27);
  private static final LocalDate MON = LocalDate.of(2027, 3, 1);
  private static final LocalDate TUE = MON.plusDays(1);
  private static final LocalDate WED = MON.plusDays(2);
  private static final LocalDate THU = MON.plusDays(3);
  private static final List<LocalDate> OPEN = List.of(SAT, MON, TUE, WED);

  @Test
  void techIsOfferedTheBusierNearbyDay() {
    List<Suggestion> s = RideAlong.suggest("Tech", MON, OPEN, Map.of(TUE, 3, MON, 1));
    assertEquals(List.of(new Suggestion(TUE, 3)), s);
  }

  @Test
  void freshAndStyleAreNeverMoved() {
    Map<LocalDate, Integer> busy = Map.of(TUE, 5);
    assertTrue(RideAlong.suggest("Fresh", MON, OPEN, busy).isEmpty());
    assertTrue(RideAlong.suggest("Style", MON, OPEN, busy).isEmpty());
  }

  @Test
  void aDayNoBusierThanTheChosenOneIsNotOffered() {
    assertTrue(RideAlong.suggest("Tech", MON, OPEN, Map.of(MON, 2, TUE, 2)).isEmpty());
  }

  @Test
  void aClosedOrNonOperatingDayIsNotOffered() {
    // THU is not in OPEN: past the cutoff, closed or not operating.
    assertTrue(RideAlong.suggest("Tech", MON, OPEN, Map.of(THU, 4)).isEmpty());
  }

  @Test
  void noBookingsMeansNoHint() {
    assertTrue(RideAlong.suggest("Tech", MON, OPEN, Map.of()).isEmpty());
  }

  @Test
  void busiestFirstThenNearestAndAtMostTwo() {
    List<Suggestion> s = RideAlong.suggest("Tech", MON, OPEN, Map.of(SAT, 2, TUE, 2, WED, 4));
    assertEquals(List.of(new Suggestion(WED, 4), new Suggestion(TUE, 2)), s);
  }

  @Test
  void daysBeyondTheReachAreIgnored() {
    LocalDate far = MON.plusDays(RideAlong.REACH_DAYS + 1);
    assertTrue(RideAlong.suggest("Tech", MON, List.of(MON, far), Map.of(far, 9)).isEmpty());
  }
}
