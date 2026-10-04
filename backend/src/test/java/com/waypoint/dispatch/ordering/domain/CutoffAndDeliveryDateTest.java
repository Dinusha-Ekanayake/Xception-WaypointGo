package com.waypoint.dispatch.ordering.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.DomainException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** ORD-01, ORD-02 and ORD-12, with the clock and the calendar as plain values. */
class CutoffAndDeliveryDateTest {

  /** 15:59:59 and 16:00:00 on Tuesday 2 March 2027 in Asia/Colombo (UTC+05:30). */
  private static final Instant BEFORE = Instant.parse("2027-03-02T10:29:59Z");

  private static final Instant AT = Instant.parse("2027-03-02T10:30:00Z");

  private static final Predicate<LocalDate> WEEKDAYS =
      d -> d.getDayOfWeek() != DayOfWeek.SUNDAY;
  private static final Predicate<LocalDate> NOTHING_CLOSED = d -> false;

  @Test
  void oneSecondBeforeTheCutoffMakesTomorrowsRun() {
    assertEquals(LocalDate.parse("2027-03-03"), Cutoff.earliestServiceDate(BEFORE));
    assertFalse(Cutoff.hasPassed(LocalDate.parse("2027-03-03"), BEFORE));
  }

  @Test
  void atTheCutoffExactlyTheOrderWaitsForTheFollowingRun() {
    assertEquals(LocalDate.parse("2027-03-04"), Cutoff.earliestServiceDate(AT));
    assertTrue(Cutoff.hasPassed(LocalDate.parse("2027-03-03"), AT));
  }

  @Test
  void theCutoffIsColomboTimeWhateverTheServerZone() {
    assertEquals(Instant.parse("2027-03-02T10:30:00Z"), Cutoff.closesAt(LocalDate.parse("2027-03-03")));
  }

  @Test
  void anOrderPastTheCutoffIsAcceptedForTheNextRunAndSaysWhy() {
    DeliveryDate date =
        DeliveryDate.resolve(LocalDate.parse("2027-03-03"), AT, WEEKDAYS, NOTHING_CLOSED);

    assertEquals(LocalDate.parse("2027-03-04"), date.delivery());
    assertTrue(date.rolled());
    assertEquals(List.of("cutoff"), date.reasons());
  }

  @Test
  void aNonOperatingDateRollsToTheNextOperatingDay() {
    // Sunday 7 March 2027 does not operate.
    DeliveryDate date =
        DeliveryDate.resolve(LocalDate.parse("2027-03-07"), BEFORE, WEEKDAYS, NOTHING_CLOSED);

    assertEquals(LocalDate.parse("2027-03-08"), date.delivery());
    assertEquals(List.of("non_operating"), date.reasons());
  }

  @Test
  void anOrderPlacedOnAHolidayForALaterOperatingDayIsUnchanged() {
    Instant sundayMorning = Instant.parse("2027-03-07T03:00:00Z");

    DeliveryDate date =
        DeliveryDate.resolve(LocalDate.parse("2027-03-08"), sundayMorning, WEEKDAYS, NOTHING_CLOSED);

    assertEquals(LocalDate.parse("2027-03-08"), date.delivery());
    assertFalse(date.rolled());
  }

  @Test
  void aDayTheDispatcherAlreadyClosedRollsForward() {
    Set<LocalDate> closed = Set.of(LocalDate.parse("2027-03-10"));

    DeliveryDate date =
        DeliveryDate.resolve(LocalDate.parse("2027-03-10"), BEFORE, WEEKDAYS, closed::contains);

    assertEquals(LocalDate.parse("2027-03-11"), date.delivery());
    assertEquals(List.of("closed"), date.reasons());
  }

  @Test
  void aCalendarWithNoOperatingDayIsAnErrorNotALoop() {
    assertThrows(
        DomainException.class,
        () -> DeliveryDate.resolve(LocalDate.parse("2027-03-10"), BEFORE, d -> false, NOTHING_CLOSED));
  }
}
