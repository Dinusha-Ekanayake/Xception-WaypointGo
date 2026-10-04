package com.waypoint.dispatch.planning.domain;

import java.util.Set;
import java.util.UUID;

/**
 * One rule, evaluated on one vehicle's whole day. Pure: no clock, no database.
 * Thresholds come from the {@link RuleSet}, never from a constant here.
 */
public interface Constraint {

  /** The vehicle's day as it would be, and the orders already placed on other vehicles. */
  record Candidate(VehicleDay day, PlanContext context, Set<UUID> placedElsewhere) {
    public Candidate {
      placedElsewhere = Set.copyOf(placedElsewhere);
    }
  }

  String ruleId();

  String name();

  ConstraintResult check(Candidate candidate);
}
