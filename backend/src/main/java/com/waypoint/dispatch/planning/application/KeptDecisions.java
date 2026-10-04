package com.waypoint.dispatch.planning.application;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationSource;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.AllocationEngine.Pin;
import com.waypoint.dispatch.planning.domain.PlanningRun.Mark;
import com.waypoint.dispatch.planning.domain.PlanningRun.Source;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.AllocationRow;
import com.waypoint.dispatch.planning.infrastructure.JdbcPlanRepository.TripRow;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a dispatcher decided that a new draft keeps: the orders they placed or
 * locked, which go back where they were, and the orders they kept deferred,
 * which are not offered a place. Read from the draft being replaced or from a
 * saved plan being returned to.
 *
 * @param marks the hand to put back on each kept order
 * @param heldDecisions the earlier explanation of each order kept deferred
 */
record KeptDecisions(
    Map<UUID, Pin> pins, Set<UUID> held, Map<UUID, Mark> marks, Map<UUID, OrderDecision> heldDecisions) {

  static final KeptDecisions NONE = new KeptDecisions(Map.of(), Set.of(), Map.of(), Map.of());

  boolean isEmpty() {
    return pins.isEmpty() && held.isEmpty();
  }

  /** From a stored draft: every order a dispatcher placed, swapped in or locked, and every one kept deferred. */
  static KeptDecisions ofDraft(List<AllocationRow> allocations, List<TripRow> trips) {
    Map<UUID, TripRow> tripById = trips.stream().collect(Collectors.toMap(TripRow::tripId, Function.identity()));
    Map<UUID, Pin> pins = new HashMap<>();
    Set<UUID> held = new HashSet<>();
    Map<UUID, Mark> marks = new HashMap<>();
    Map<UUID, OrderDecision> heldDecisions = new HashMap<>();
    for (AllocationRow a : allocations) {
      if (a.decidedBy().isEmpty()) {
        continue;
      }
      Source source = Source.valueOf(a.source().toUpperCase(Locale.ROOT));
      Mark mark = new Mark(source, a.decidedBy().get(), a.locked());
      if (a.decision() == AllocationDecision.SERVED && a.tripId().isPresent()) {
        TripRow trip = tripById.get(a.tripId().get());
        pins.put(a.orderId(), new Pin(trip.vehicleId(), trip.tripNumber()));
        marks.put(a.orderId(), mark);
      } else if (a.decision() == AllocationDecision.DEFERRED && (source == Source.KEPT || source == Source.MANUAL_DEFER)) {
        held.add(a.orderId());
        marks.put(a.orderId(), mark);
        heldDecisions.put(
            a.orderId(),
            new OrderDecision(
                a.orderId(), AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(), a.bindingRule(),
                a.reason(), a.checks()));
      }
    }
    return new KeptDecisions(pins, held, marks, heldDecisions);
  }

  /**
   * From a saved plan: every placement goes back. An order the engine placed is
   * restored by whoever restores it; one a dispatcher decided keeps their name.
   */
  static KeptDecisions ofSnapshot(PlanView plan, UUID restorer) {
    Map<UUID, TripView> tripById = new HashMap<>();
    plan.trips().forEach(t -> tripById.put(t.tripId(), t));
    Map<UUID, Pin> pins = new HashMap<>();
    Set<UUID> held = new HashSet<>();
    Map<UUID, Mark> marks = new HashMap<>();
    Map<UUID, OrderDecision> heldDecisions = new HashMap<>();
    for (AllocationView a : plan.allocations()) {
      if (a.decision() == AllocationDecision.SERVED && a.tripId().isPresent()) {
        TripView trip = tripById.get(a.tripId().get());
        pins.put(a.orderId(), new Pin(trip.vehicleId(), trip.tripNumber()));
        boolean engine = a.source() == AllocationSource.ENGINE;
        marks.put(
            a.orderId(),
            new Mark(
                engine ? Source.RESTORED : Source.valueOf(a.source().name()),
                engine ? restorer : a.decidedBy().orElse(restorer),
                a.locked()));
      } else if (a.decision() == AllocationDecision.DEFERRED
          && (a.source() == AllocationSource.KEPT || a.source() == AllocationSource.MANUAL_DEFER)) {
        held.add(a.orderId());
        marks.put(
            a.orderId(), new Mark(Source.valueOf(a.source().name()), a.decidedBy().orElse(restorer), false));
        heldDecisions.put(
            a.orderId(),
            new OrderDecision(
                a.orderId(), AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(), a.bindingRule(),
                a.reason(), List.of()));
      }
    }
    return new KeptDecisions(pins, held, marks, heldDecisions);
  }

  /** Only the orders still in the day's demand: a cancelled order's decision has nothing to hold. */
  KeptDecisions within(Set<UUID> demand) {
    return new KeptDecisions(
        Map.copyOf(pins.entrySet().stream().filter(e -> demand.contains(e.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))),
        held.stream().filter(demand::contains).collect(Collectors.toSet()),
        Map.copyOf(marks.entrySet().stream().filter(e -> demand.contains(e.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))),
        Map.copyOf(heldDecisions.entrySet().stream().filter(e -> demand.contains(e.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
  }
}
