package com.waypoint.dispatch.planning.domain;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.WhatIfOrderView;
import com.waypoint.dispatch.planning.contract.PlanViews.WhatIfView;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Roadmap AI-1: the difference between two runs of the same orders. Pure. */
public final class WhatIf {
  private WhatIf() {}

  public static WhatIfView compare(
      UUID planId, String vehicleId, List<PlanOrder> orders, AllocationResult before, AllocationResult after) {
    Set<UUID> was = served(before);
    Set<UUID> now = served(after);
    return new WhatIfView(planId, vehicleId, was.size(), now.size(), chilled(orders, was), chilled(orders, now),
        vehicles(before), vehicles(after), trips(before), trips(after),
        list(orders, now, was), list(orders, was, now));
  }

  private static Set<UUID> served(AllocationResult r) {
    return r.decisions().stream()
        .filter(d -> d.decision() == AllocationDecision.SERVED)
        .map(AllocationEngine.OrderDecision::orderId)
        .collect(Collectors.toSet());
  }

  private static BigDecimal chilled(List<PlanOrder> orders, Set<UUID> served) {
    return orders.stream()
        .filter(o -> served.contains(o.orderId()) && "chilled".equals(o.temperature()))
        .map(PlanOrder::volumeM3)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static int vehicles(AllocationResult r) {
    return (int) r.days().stream().filter(d -> !d.trips().isEmpty()).count();
  }

  private static int trips(AllocationResult r) {
    return r.days().stream().mapToInt(d -> d.trips().size()).sum();
  }

  /** Orders in {@code in} and not in {@code notIn}, by reference. */
  private static List<WhatIfOrderView> list(List<PlanOrder> orders, Set<UUID> in, Set<UUID> notIn) {
    return orders.stream()
        .filter(o -> in.contains(o.orderId()) && !notIn.contains(o.orderId()))
        .sorted(Comparator.comparing(PlanOrder::orderRef))
        .map(o -> new WhatIfOrderView(o.orderId(), o.orderRef(), o.outletId(), o.brand(), o.temperature(), o.volumeM3()))
        .toList();
  }
}
