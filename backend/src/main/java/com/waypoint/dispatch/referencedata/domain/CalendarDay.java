package com.waypoint.dispatch.referencedata.domain;

import java.time.LocalDate;

/**
 * One operating date and its context.
 *
 * @param generated true when this day was produced by policy beyond the supplied
 *     range rather than supplied. R-CAL-03: a dispatcher must be able to tell a
 *     real holiday from an assumed one.
 */
public record CalendarDay(
    LocalDate date,
    int dayOfWeek,
    boolean weekend,
    int isoYear,
    int isoWeek,
    boolean payday,
    String festival,
    java.math.BigDecimal festivalRamp,
    boolean holiday,
    boolean monsoon,
    boolean operating,
    boolean generated) {}
