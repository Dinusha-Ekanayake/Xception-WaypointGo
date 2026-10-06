package com.waypoint.dispatch.support;

import java.time.DayOfWeek;
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
 * the set keeps one run clear of itself.
 *
 * <p>Each day also takes its whole week, with a free week on either side: the
 * weekly fuel quota (R-PLN-16) counts every plan in a week, so two tests in one
 * week shared a vehicle's litres and a replan chose another truck depending on
 * the order the tests ran in. Tests that use the day before or after stay in
 * their own or a free week. The window ends before 2286, where
 * epoch seconds reach the 9999999999 that tests use as a far-future expiry.
 */
public final class TestDates {
  private static final LocalDate START = LocalDate.of(2100, 1, 1);
  private static final int SPAN_DAYS = 60_000;
  private static final Set<LocalDate> GIVEN = ConcurrentHashMap.newKeySet();
  /** The Monday of every week a test was given. */
  private static final Set<LocalDate> WEEKS = ConcurrentHashMap.newKeySet();

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
      if (claimWeek(day) && GIVEN.add(day)) {
        return day;
      }
    }
  }

  private static synchronized boolean claimWeek(LocalDate day) {
    LocalDate monday = day.with(DayOfWeek.MONDAY);
    if (WEEKS.contains(monday) || WEEKS.contains(monday.minusWeeks(1)) || WEEKS.contains(monday.plusWeeks(1))) {
      return false;
    }
    return WEEKS.add(monday);
  }
}
