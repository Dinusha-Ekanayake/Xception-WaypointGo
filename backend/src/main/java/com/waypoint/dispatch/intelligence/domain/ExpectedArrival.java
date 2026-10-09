package com.waypoint.dispatch.intelligence.domain;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A stop's expected arrival before the trip leaves (roadmap T1-02, R-ML-22):
 * its planned arrival, later by however much longer the stops before it are
 * expected to take than the plan allowed. A stop expected to be quicker never
 * pulls a later one earlier than planned: a vehicle early at a store waits for
 * the window. Pure.
 */
public final class ExpectedArrival {
  private ExpectedArrival() {}

  /** One stop of a trip, in order: when the plan has it arrive, and its minutes as planned and as predicted. */
  public record Stop(LocalTime plannedArrival, double plannedServiceMinutes, double expectedServiceMinutes) {}

  /** The expected arrival of each stop, in the order given. */
  public static List<LocalTime> beforeDeparture(List<Stop> trip) {
    List<LocalTime> out = new ArrayList<>();
    double behind = 0;
    for (Stop s : trip) {
      out.add(s.plannedArrival().plusSeconds(Math.round(Math.max(0, behind) * 60)));
      behind = Math.max(0, behind + s.expectedServiceMinutes() - s.plannedServiceMinutes());
    }
    return out;
  }

  /** "About" a time: to the nearest five minutes, as it is told to people. */
  public static LocalTime about(LocalTime t) {
    int minutes = t.getHour() * 60 + t.getMinute() + (t.getSecond() >= 30 ? 1 : 0);
    int rounded = Math.min(23 * 60 + 55, (int) (Math.round(minutes / 5.0) * 5));
    return LocalTime.of(rounded / 60, rounded % 60);
  }
}
