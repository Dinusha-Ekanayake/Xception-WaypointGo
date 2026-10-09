package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * R-PLN-44: a trip whose truck has left is frozen. A revision may change any
 * trip still at the depot (its loader re-checks what changed, R-LOD-07), but
 * not one that is on the road: Loading keeps the manifest the driver left
 * with, so a change to it would make the published plan disagree with the
 * truck. Pure: the plans before and after and the released trips in, the
 * released trips the revision would touch out.
 */
public final class ReleasedTripFreeze {
  private ReleasedTripFreeze() {}

  /**
   * Released trips the revision changes (another vehicle, time or stops) or
   * removes, in the order of the plan before. Empty when it may be published.
   */
  public static List<UUID> touched(PlanView before, PlanView after, Set<UUID> released) {
    if (released.isEmpty()) {
      return List.of();
    }
    Set<UUID> touched = new LinkedHashSet<>(PlanDiff.changedTrips(before, after));
    touched.addAll(PlanDiff.removedTrips(before, after));
    return before.trips().stream()
        .map(TripView::tripId)
        .filter(id -> released.contains(id) && touched.contains(id))
        .toList();
  }
}
