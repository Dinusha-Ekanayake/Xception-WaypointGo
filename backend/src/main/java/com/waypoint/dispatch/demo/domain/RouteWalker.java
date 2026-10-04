package com.waypoint.dispatch.demo.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The path a simulated vehicle drives (issue #231): straight legs from the depot
 * through each stop, one point per tick, pausing a few ticks at every stop. Pure:
 * the same waypoints always give the same path, so a tick index is all a
 * simulation needs to remember. Straight legs are labelled as such on screen;
 * no road geometry or exact location is invented.
 */
public final class RouteWalker {
  public static final double METRES_PER_POINT = 400;
  public static final int MIN_POINTS_PER_LEG = 4;
  public static final int MAX_POINTS_PER_LEG = 45;
  public static final int DWELL_POINTS = 4;

  public record Waypoint(double latitude, double longitude) {}

  private RouteWalker() {}

  public static List<Waypoint> path(List<Waypoint> waypoints) {
    List<Waypoint> path = new ArrayList<>();
    if (waypoints.isEmpty()) return path;
    path.add(waypoints.get(0));
    for (int leg = 1; leg < waypoints.size(); leg++) {
      Waypoint from = waypoints.get(leg - 1);
      Waypoint to = waypoints.get(leg);
      int points = Math.max(MIN_POINTS_PER_LEG,
          Math.min(MAX_POINTS_PER_LEG, (int) Math.ceil(metres(from, to) / METRES_PER_POINT)));
      for (int i = 1; i <= points; i++) {
        double t = (double) i / points;
        path.add(new Waypoint(from.latitude() + (to.latitude() - from.latitude()) * t,
            from.longitude() + (to.longitude() - from.longitude()) * t));
      }
      for (int i = 0; i < DWELL_POINTS; i++) path.add(to);
    }
    return List.copyOf(path);
  }

  /** Degrees clockwise from north from one point to the next, or null when they coincide. */
  public static Double heading(Waypoint from, Waypoint to) {
    double dLat = to.latitude() - from.latitude();
    double dLon = (to.longitude() - from.longitude()) * Math.cos(Math.toRadians(from.latitude()));
    if (dLat == 0 && dLon == 0) return null;
    double degrees = (Math.toDegrees(Math.atan2(dLon, dLat)) + 360) % 360;
    return Math.min(359.9, Math.round(degrees * 10) / 10.0);
  }

  static double metres(Waypoint a, Waypoint b) {
    double r = 6_371_000;
    double dLat = Math.toRadians(b.latitude() - a.latitude());
    double dLon = Math.toRadians(b.longitude() - a.longitude());
    double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(a.latitude())) * Math.cos(Math.toRadians(b.latitude()))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * r * Math.asin(Math.sqrt(h));
  }
}
