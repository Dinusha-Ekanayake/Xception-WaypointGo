package com.waypoint.dispatch.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.demo.domain.RouteWalker;
import com.waypoint.dispatch.demo.domain.RouteWalker.Waypoint;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteWalkerTest {
  private static final Waypoint DEPOT = new Waypoint(6.9665, 79.8862);
  private static final Waypoint STORE = new Waypoint(6.9271, 79.8612);

  @Test
  void startsAtTheDepotEndsAtTheLastStopAndWaitsAtEachStop() {
    List<Waypoint> path = RouteWalker.path(List.of(DEPOT, STORE));
    assertEquals(DEPOT, path.get(0));
    assertEquals(STORE, path.get(path.size() - 1));
    long atStore = path.stream().filter(STORE::equals).count();
    assertEquals(RouteWalker.DWELL_POINTS + 1, atStore);
  }

  @Test
  void legLengthIsBoundedEvenForZeroOrHugeDistances() {
    List<Waypoint> same = RouteWalker.path(List.of(DEPOT, DEPOT));
    assertEquals(1 + RouteWalker.MIN_POINTS_PER_LEG + RouteWalker.DWELL_POINTS, same.size());
    List<Waypoint> far = RouteWalker.path(List.of(DEPOT, new Waypoint(9.6615, 80.0255)));
    assertEquals(1 + RouteWalker.MAX_POINTS_PER_LEG + RouteWalker.DWELL_POINTS, far.size());
    assertTrue(RouteWalker.path(List.of()).isEmpty());
  }

  @Test
  void headingPointsTheWayTheVehicleMoves() {
    assertNull(RouteWalker.heading(DEPOT, DEPOT));
    double south = RouteWalker.heading(DEPOT, new Waypoint(6.9, 79.8862));
    assertEquals(180.0, south, 0.1);
  }

  @Test
  void eachStopIsArrivedAtItsFirstPauseAndDeliveredAtItsLast() {
    Waypoint second = new Waypoint(6.9000, 79.8700);
    List<Waypoint> waypoints = List.of(DEPOT, STORE, second);
    List<Waypoint> path = RouteWalker.path(waypoints);
    List<RouteWalker.StopTicks> stops = RouteWalker.stops(waypoints);
    assertEquals(2, stops.size());
    for (int i = 0; i < stops.size(); i++) {
      Waypoint at = waypoints.get(i + 1);
      RouteWalker.StopTicks t = stops.get(i);
      assertEquals(RouteWalker.DWELL_POINTS - 1, t.done() - t.arrive());
      for (int tick = t.arrive(); tick <= t.done(); tick++) assertEquals(at, path.get(tick), "paused at the stop");
      assertTrue(!path.get(t.done() + 1 < path.size() ? t.done() + 1 : t.done()).equals(at) || t.done() == path.size() - 1,
          "it leaves after the last pause");
    }
    assertEquals(path.size() - 1, stops.get(1).done(), "the last stop's delivery is the last tick");
  }
}
