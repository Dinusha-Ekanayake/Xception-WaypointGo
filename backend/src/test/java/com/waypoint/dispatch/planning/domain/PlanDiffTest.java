package com.waypoint.dispatch.planning.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationSource;
import com.waypoint.dispatch.planning.contract.PlanViews.AllocationView;
import com.waypoint.dispatch.planning.contract.PlanViews.ChangeKind;
import com.waypoint.dispatch.planning.contract.PlanViews.ComparisonView;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanView;
import com.waypoint.dispatch.planning.contract.PlanViews.StopView;
import com.waypoint.dispatch.planning.contract.PlanViews.TripView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What differs between two plans of a day, and which trips a driver would see differently. */
class PlanDiffTest {
  final UUID x = UUID.fromString("00000000-0000-0000-0000-000000000001");
  final UUID y = UUID.fromString("00000000-0000-0000-0000-000000000002");
  final UUID z = UUID.fromString("00000000-0000-0000-0000-000000000003");
  final UUID trip1 = UUID.randomUUID();
  final UUID trip2 = UUID.randomUUID();

  StopView stop(UUID order, String outlet, int sequence, String arrival) {
    return new StopView(sequence, order, outlet, LocalTime.parse(arrival), LocalTime.of(3, 0), LocalTime.of(8, 0), BigDecimal.TEN);
  }

  TripView trip(UUID id, String vehicle, int number, StopView... stops) {
    return new TripView(
        id, vehicle, number, "Fresh", "Colombo", "ambient", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN,
        LocalTime.of(3, 30), List.of(stops));
  }

  AllocationView served(UUID order, UUID trip) {
    return new AllocationView(
        order, AllocationDecision.SERVED, Optional.of(trip), Optional.empty(), "served", List.of(),
        AllocationSource.ENGINE, false, Optional.empty(), Optional.empty(), Optional.empty());
  }

  AllocationView deferred(UUID order) {
    return new AllocationView(
        order, AllocationDecision.DEFERRED, Optional.empty(), Optional.of("R-PLN-06"), "no room", List.of(),
        AllocationSource.ENGINE, false, Optional.empty(), Optional.empty(), Optional.empty());
  }

  PlanView plan(List<TripView> trips, List<AllocationView> allocations) {
    return new PlanView(
        UUID.randomUUID(), "Peliyagoda", LocalDate.of(2026, 10, 5), 1, PlanStatus.DRAFT, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), Optional.empty(), Instant.EPOCH, true, trips,
        allocations, 1, "test", Optional.empty(), Optional.empty());
  }

  @Test
  void anOrderOnAnotherVehicleIsMovedOneAddedAndOneDropped() {
    PlanView a =
        plan(
            List.of(trip(trip1, "T1", 1, stop(x, "OUT-X", 1, "04:00"), stop(y, "OUT-Y", 2, "04:30"))),
            List.of(served(x, trip1), served(y, trip1), deferred(z)));
    PlanView b =
        plan(
            List.of(
                trip(trip1, "T1", 1, stop(x, "OUT-X", 1, "04:00")),
                trip(trip2, "T2", 1, stop(z, "OUT-Z", 1, "04:10"))),
            List.of(served(x, trip1), deferred(y), served(z, trip2)));

    ComparisonView diff = PlanDiff.compare("Plan A", a, "Plan B", b);

    assertEquals(2, diff.a().served());
    assertEquals(2, diff.b().served());
    assertEquals(1, diff.a().trips());
    assertEquals(2, diff.b().trips());
    assertEquals(2, diff.changes().size());
    assertEquals(ChangeKind.DROPPED, diff.changes().stream().filter(c -> c.orderId().equals(y)).findFirst().orElseThrow().kind());
    assertEquals(ChangeKind.ADDED, diff.changes().stream().filter(c -> c.orderId().equals(z)).findFirst().orElseThrow().kind());
    assertEquals(List.of("OUT-Z"), diff.affectedOutlets(), "a dropped order is told as a deferral, not as a change");
  }

  @Test
  void anOrderServedByBothOnADifferentVehicleIsMoved() {
    PlanView a = plan(List.of(trip(trip1, "T1", 1, stop(x, "OUT-X", 1, "04:00"))), List.of(served(x, trip1)));
    PlanView b = plan(List.of(trip(trip2, "T2", 1, stop(x, "OUT-X", 1, "04:00"))), List.of(served(x, trip2)));

    ComparisonView diff = PlanDiff.compare("A", a, "B", b);

    assertEquals(1, diff.changes().size());
    assertEquals(ChangeKind.MOVED, diff.changes().get(0).kind());
    assertEquals(Optional.of("T1"), diff.changes().get(0).before().vehicleId());
    assertEquals(Optional.of("T2"), diff.changes().get(0).after().vehicleId());
  }

  @Test
  void aTripOnAnotherVehicleOrAtAnotherTimeIsChangedEvenWithTheSameId() {
    PlanView a = plan(List.of(trip(trip1, "T1", 1, stop(x, "OUT-X", 1, "04:00"))), List.of(served(x, trip1)));
    PlanView otherVehicle = plan(List.of(trip(trip1, "T2", 1, stop(x, "OUT-X", 1, "04:00"))), List.of(served(x, trip1)));
    PlanView otherTime = plan(List.of(trip(trip1, "T1", 1, stop(x, "OUT-X", 1, "04:20"))), List.of(served(x, trip1)));
    PlanView same = plan(List.of(trip(trip1, "T1", 1, stop(x, "OUT-X", 1, "04:00"))), List.of(served(x, trip1)));

    assertEquals(List.of(trip1), PlanDiff.changedTrips(a, otherVehicle), "the driver of the new vehicle must be told");
    assertEquals(List.of(trip1), PlanDiff.changedTrips(a, otherTime));
    assertTrue(PlanDiff.changedTrips(a, same).isEmpty(), "an untouched trip is nobody's news");
  }

  @Test
  void anOutletReachedAtAnotherTimeIsAffectedEvenIfItsOrderDidNotMove() {
    PlanView a =
        plan(
            List.of(trip(trip1, "T1", 1, stop(x, "OUT-X", 1, "04:00"), stop(y, "OUT-Y", 2, "04:30"))),
            List.of(served(x, trip1), served(y, trip1)));
    PlanView b =
        plan(
            List.of(trip(trip1, "T1", 1, stop(y, "OUT-Y", 1, "04:00"), stop(x, "OUT-X", 2, "04:30"))),
            List.of(served(x, trip1), served(y, trip1)));

    assertEquals(List.of("OUT-X", "OUT-Y"), PlanDiff.affectedOutlets(a, b));
    assertTrue(PlanDiff.affectedOutlets(a, a).isEmpty());
  }

  @Test
  void aTripThatIsGoneIsRemovedAndANewOneIsChanged() {
    PlanView a = plan(List.of(trip(trip1, "T1", 1, stop(x, "OUT-X", 1, "04:00"))), List.of(served(x, trip1)));
    PlanView b = plan(List.of(trip(trip2, "T1", 1, stop(y, "OUT-Y", 1, "04:00"))), List.of(served(y, trip2)));

    assertEquals(List.of(trip1), PlanDiff.removedTrips(a, b));
    assertEquals(List.of(trip2), PlanDiff.changedTrips(a, b));
  }
}
