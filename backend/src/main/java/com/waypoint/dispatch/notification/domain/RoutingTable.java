package com.waypoint.dispatch.notification.domain;

import java.util.List;

/** One published version of the routing matrix. */
public record RoutingTable(int version, List<RoutingRule> rules) {

  public RoutingTable {
    rules = List.copyOf(rules);
  }

  public List<RoutingRule> rulesFor(String eventType) {
    return rules.stream().filter(r -> r.eventType().equals(eventType)).toList();
  }
}
