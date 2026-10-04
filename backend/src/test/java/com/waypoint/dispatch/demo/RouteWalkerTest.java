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
}
