package com.waypoint.dispatch.referencedata.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.DomainException;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The arrival rules the booklet states precisely, and which are easy to get
 * subtly wrong: early waits, and late is measured against the close rather than
 * against the plan.
 */
class DeliveryWindowTest {
  private static final DeliveryWindow FRESH = window(5, 0, 7, 30);

  @Test
  void aWindowMustOpenBeforeItCloses() {
    assertThrows(DomainException.class, () -> window(7, 30, 5, 0));
  }

  @Test
  void arrivingEarlyMeansWaiting() {
    // R-EXE-13: handling starts at the open, not at arrival.
    assertEquals(LocalTime.of(5, 0), FRESH.serviceStartsAt(LocalTime.of(4, 20)));
  }

  @Test
  void arrivingInsideTheWindowStartsImmediately() {
    assertEquals(LocalTime.of(6, 15), FRESH.serviceStartsAt(LocalTime.of(6, 15)));
  }

  @Test
  void laterThanPlannedButInsideTheWindowIsNotLate() {
    // R-EXE-14 and edge case EXE-16.
    assertFalse(FRESH.isLate(LocalTime.of(7, 29)));
  }

  @Test
  void afterTheCloseIsLateEvenByAMinute() {
    assertTrue(FRESH.isLate(LocalTime.of(7, 31)));
  }

  @Test
  void aMallOutletIsBoundByTheOverlapOfBothWindows() {
    // R-PLN-29: the effective window is the intersection.
    Optional<DeliveryWindow> effective = window(9, 0, 13, 0).intersect(window(10, 30, 12, 30));

    assertEquals(Optional.of(window(10, 30, 12, 30)), effective);
  }

  @Test
  void windowsThatDoNotOverlapLeaveTheOutletUnservable() {
    // Edge case EXE-19: surfaced, never silently attempted.
    assertTrue(window(5, 0, 7, 0).intersect(window(9, 0, 11, 0)).isEmpty());
  }

  @Test
  void durationIsInMinutes() {
    assertEquals(150, FRESH.durationMinutes());
  }

  private static DeliveryWindow window(int openHour, int openMin, int closeHour, int closeMin) {
    return new DeliveryWindow(
        LocalTime.of(openHour, openMin), LocalTime.of(closeHour, closeMin));
  }
}
