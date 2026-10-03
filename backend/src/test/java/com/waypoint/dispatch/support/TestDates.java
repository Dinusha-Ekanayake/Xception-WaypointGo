package com.waypoint.dispatch.support;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;

/**
 * A service day no other test in this JVM has been given. Integration tests
 * share one database and mostly one depot, so a test that reads "the depot's
 * orders for a day" sees another test's orders when both drew the same random
 * day. A wide window keeps a rerun against a kept database clear of old rows;
 * the set keeps one run clear of itself. The window ends before 2286, where
 * epoch seconds reach the 9999999999 that tests use as a far-future expiry.
 */
public final class TestDates {
  private static final LocalDate START = LocalDate.of(2100, 1, 1);
  private static final int SPAN_DAYS = 60_000;
  private static final Set<LocalDate> GIVEN = ConcurrentHashMap.newKeySet();

  private TestDates() {}

  /** An unused day, taken as it is. */
  public static LocalDate unusedDay() {
    return unusedDay(UnaryOperator.identity());
  }

  /**
   * An unused day after {@code normalise}, usually {@code reference::nextOperatingDay},
   * which can move two different draws onto the same day.
   */
  public static LocalDate unusedDay(UnaryOperator<LocalDate> normalise) {
    while (true) {
      LocalDate day = normalise.apply(START.plusDays(ThreadLocalRandom.current().nextInt(0, SPAN_DAYS)));
      if (GIVEN.add(day)) {
        return day;
      }
    }
  }
}
