package com.waypoint.dispatch.planning.domain;

import java.util.Map;

/** What every constraint may read besides the vehicle's day. */
public record PlanContext(String depotCode, Map<String, DistrictTravel> travel, RuleSet rules) {
  public PlanContext {
    travel = Map.copyOf(travel);
  }
}
