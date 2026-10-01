package com.waypoint.dispatch.planning.domain;

import static com.waypoint.dispatch.planning.domain.PlanningFixtures.CONTEXT;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.order;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.reefer;
import static com.waypoint.dispatch.planning.domain.PlanningFixtures.truck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.planning.contract.PlanViews.AllocationDecision;
import com.waypoint.dispatch.planning.contract.PlanViews.PlanStatus;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.AllocationEngine.OrderDecision;
import com.waypoint.dispatch.planning.domain.PlanningRun.Stamps;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Dispatcher decisions on a draft: each returns the next version, through the same registry. */
class PlanningRunTest {
  static final UUID ENGINE = UUID.randomUUID();
  static final UUID DISPATCHER = UUID.randomUUID();
  static final ConstraintRegistry REGISTRY = ConstraintRegistry.standard();

  final FleetVehicle t1 = truck("T1");
  final FleetVehicle t2 = truck("T2");
  final FleetVehicle r1 = reefer("R1");
  final PlanOrder a = order().build();
  final PlanOrder b = order().build();
  final PlanOrder c = order().chilled().build();

  /** a and b on T1 trip 1; c deferred by the engine on reefer capacity. */
  PlanningRun draft() {
    VehicleDay day = VehicleDay.idle(t1).withNewTrip(a).withJoined(1, b);
    AllocationResult result =
        new AllocationResult(
            List.of(day),
            List.of(
                served(a, "T1"),
                served(b, "T1"),
                new OrderDecision(c.orderId(), AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(),
                    Optional.of("R-PLN-06"), "no reefer had room", List.of())),
            false,
            "test");
    return PlanningRun.draft(
        UUID.randomUUID(), "Peliyagoda", LocalDate.of(2026, 10, 5), 1,
        new Stamps(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
        Optional.empty(), "fp", result, ENGINE);
  }

  @Test
  void anOverrideReturnsTheNextVersionAndLeavesThisOneAlone() {
    PlanningRun before = draft();
    UUID nextId = UUID.randomUUID();

    PlanningRun next = before.override(nextId, 2, b, t2, 1, "closer to the outlet", REGISTRY, CONTEXT);

    assertEquals(nextId, next.planId());
    assertEquals(2, next.planVersion());
    assertEquals(PlanStatus.DRAFT, next.status());
    OrderDecision moved = next.decisionFor(b.orderId()).orElseThrow();
    assertEquals(Optional.of("T2"), moved.vehicleId());
    assertEquals(Optional.of(1), moved.tripNumber());
    assertEquals("override: closer to the outlet", moved.reason());
    assertFalse(moved.checks().isEmpty(), "the UI reads every check of the new place");
    assertEquals(
        before.decisionFor(a.orderId()).orElseThrow().reason(),
        next.decisionFor(a.orderId()).orElseThrow().reason(),
        "an order that did not move keeps its reason");
    assertEquals(Optional.of("T1"), before.decisionFor(b.orderId()).orElseThrow().vehicleId(), "a version is never edited");
    assertTrue(next.verify(Set.of(a.orderId(), b.orderId(), c.orderId()), CONTEXT, REGISTRY).isEmpty());
  }

  @Test
  void anOverrideThatBreaksARuleIsRefusedWithThatRule() {
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> draft().override(UUID.randomUUID(), 2, c, t2, 1, "dispatcher insists", REGISTRY, CONTEXT));
    assertEquals(ErrorCode.CONSTRAINT_VIOLATED, refused.code());
    assertTrue(refused.rules().contains("R-PLN-02"), refused.rules().toString());
  }

  @Test
  void anOverrideToATripThatCannotExistIsInvalid() {
    DomainException invalid =
        assertThrows(
            DomainException.class,
            () -> draft().override(UUID.randomUUID(), 2, a, t1, 3, "third trip", REGISTRY, CONTEXT));
    assertEquals(ErrorCode.VALIDATION_FAILED, invalid.code());
  }

  @Test
  void overridingADeferredOrderServesItAndClearsTheDeferral() {
    PlanningRun before = draft();
    assertEquals(ENGINE, before.deferredBy().get(c.orderId()));

    PlanningRun next = before.override(UUID.randomUUID(), 2, c, r1, 1, "reefer freed up", REGISTRY, CONTEXT);

    assertEquals(AllocationDecision.SERVED, next.decisionFor(c.orderId()).orElseThrow().decision());
    assertFalse(next.deferredBy().containsKey(c.orderId()));
  }

  @Test
  void aDeferralNamesTheDispatcherAndRuleNineteen() {
    PlanningRun next = draft().defer(UUID.randomUUID(), 2, a.orderId(), "outlet closed for stocktake", DISPATCHER, REGISTRY, CONTEXT);

    OrderDecision deferred = next.decisionFor(a.orderId()).orElseThrow();
    assertEquals(AllocationDecision.DEFERRED, deferred.decision());
    assertEquals(Optional.of(PlanningRun.MANUAL_DEFERRAL_RULE), deferred.bindingRule());
    assertEquals(DISPATCHER, next.deferredBy().get(a.orderId()), "rule 8: who decided");
    assertEquals(Optional.of(1), next.decisionFor(b.orderId()).orElseThrow().tripNumber());
    assertTrue(next.verify(Set.of(a.orderId(), b.orderId(), c.orderId()), CONTEXT, REGISTRY).isEmpty());
  }

  @Test
  void onlyAServedOrderCanBeDeferred() {
    DomainException invalid =
        assertThrows(
            DomainException.class,
            () -> draft().defer(UUID.randomUUID(), 2, c.orderId(), "again", DISPATCHER, REGISTRY, CONTEXT));
    assertEquals(ErrorCode.VALIDATION_FAILED, invalid.code());
  }

  @Test
  void aPublishedRunIsNeverChanged() {
    PlanningRun d = draft();
    PlanningRun published =
        new PlanningRun(
            d.planId(), d.depotCode(), d.serviceDate(), d.planVersion(), PlanStatus.PUBLISHED, d.stamps(),
            d.supersedes(), d.revisionReason(), d.demandFingerprint(), false, false, d.engine(), d.days(), d.decisions(),
            d.deferredBy(), 2);
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> published.defer(UUID.randomUUID(), 2, a.orderId(), "late change", DISPATCHER, REGISTRY, CONTEXT));
    assertEquals(ErrorCode.CONFLICT, refused.code());
    assertEquals(List.of("R-PLN-28"), refused.rules());
  }

  @Test
  void anUnknownOrderIsNotFound() {
    PlanOrder stranger = order().build();
    DomainException missing =
        assertThrows(
            DomainException.class,
            () -> draft().override(UUID.randomUUID(), 2, stranger, t2, 1, "who is this", REGISTRY, CONTEXT));
    assertEquals(ErrorCode.NOT_FOUND, missing.code());
    assertNotEquals(stranger.orderId(), a.orderId());
  }

  // ---- revisions and replanning (step 6) -------------------------------------

  PlanningRun published(PlanningRun d) {
    return new PlanningRun(
        d.planId(), d.depotCode(), d.serviceDate(), d.planVersion(), PlanStatus.PUBLISHED, d.stamps(),
        d.supersedes(), d.revisionReason(), d.demandFingerprint(), false, false, d.engine(), d.days(), d.decisions(),
        d.deferredBy(), 2);
  }

  /** c served alone on the reefer, a and b on truck T1. */
  PlanningRun withChilledTrip() {
    return draft().override(UUID.randomUUID(), 2, c, r1, 1, "reefer free", REGISTRY, CONTEXT);
  }

  @Test
  void aRevisionDropsCancelledOrdersAndDefersOnesThatArrivedAfterPublication() {
    PlanningRun plan = published(draft());
    UUID late = UUID.randomUUID();
    Stamps now = new Stamps(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    PlanningRun revision =
        plan.revision(UUID.randomUUID(), 2, now, "fp2", Set.of(a.orderId(), c.orderId(), late),
            "b cancelled", ENGINE, REGISTRY, CONTEXT);

    assertEquals(PlanStatus.DRAFT, revision.status());
    assertEquals(Optional.of(plan.planId()), revision.supersedes());
    assertEquals(Optional.of("b cancelled"), revision.revisionReason());
    assertEquals(now, revision.stamps(), "a revision is built under what is in force now");
    assertTrue(revision.decisionFor(b.orderId()).isEmpty(), "a cancelled order leaves the plan");
    assertFalse(revision.days().get(0).carries(b.orderId()));
    OrderDecision arrived = revision.decisionFor(late).orElseThrow();
    assertEquals(AllocationDecision.DEFERRED, arrived.decision());
    assertEquals(Optional.of(PlanningRun.ARRIVED_AFTER_PUBLICATION_RULE), arrived.bindingRule());
    assertEquals(PlanStatus.PUBLISHED, plan.status(), "the published plan itself is untouched");
  }

  @Test
  void onlyAPublishedPlanIsRevised() {
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> draft().revision(UUID.randomUUID(), 2, draft().stamps(), "fp", Set.of(), "why", ENGINE, REGISTRY, CONTEXT));
    assertEquals(List.of("R-PLN-28"), refused.rules());
  }

  @Test
  void aTripMovesWholeOntoAVehicleThatCanTakeIt() {
    PlanningRun.TripMove move = draft().moveTrip("T1", 1, t2, REGISTRY, CONTEXT);
    assertTrue(move.feasible());
    assertTrue(move.from().trips().isEmpty());
    assertEquals(Set.of(a.orderId(), b.orderId()),
        Set.copyOf(move.to().trip(1).orders().stream().map(PlanOrder::orderId).toList()));
  }

  @Test
  void aChilledTripCannotMoveToAnAmbientTruck() {
    PlanningRun.TripMove move = withChilledTrip().moveTrip("R1", 1, t2, REGISTRY, CONTEXT);
    assertFalse(move.feasible());
    assertTrue(move.checks().stream().anyMatch(r -> !r.passed() && r.ruleId().equals("R-PLN-02")));
  }

  @Test
  void aReplanTakesTheFirstCandidateThatFitsAndTouchesNothingElse() {
    PlanningRun before = withChilledTrip();

    PlanningRun.Replanned replanned =
        before.replanTrip(UUID.randomUUID(), 3, "T1", 1, List.of(t1, t2), "brakes", DISPATCHER, REGISTRY, CONTEXT);

    assertEquals(Optional.of("T2"), replanned.movedTo(), "never back onto the vehicle being replaced");
    PlanningRun next = replanned.run();
    assertEquals(Optional.of("T2"), next.decisionFor(a.orderId()).orElseThrow().vehicleId());
    assertTrue(next.decisionFor(a.orderId()).orElseThrow().reason().startsWith("replanned onto T2"));
    assertEquals(before.decisionFor(c.orderId()), next.decisionFor(c.orderId()), "PLN-04: other trips untouched");
  }

  @Test
  void aTripNoVehicleCanTakeDefersAsAUnitUnderTheRuleThatStoppedIt() {
    PlanningRun.Replanned replanned =
        withChilledTrip().replanTrip(UUID.randomUUID(), 3, "R1", 1, List.of(t2), "compressor failed", DISPATCHER,
            REGISTRY, CONTEXT);

    assertTrue(replanned.movedTo().isEmpty());
    OrderDecision deferred = replanned.run().decisionFor(c.orderId()).orElseThrow();
    assertEquals(AllocationDecision.DEFERRED, deferred.decision());
    assertEquals(Optional.of("R-PLN-02"), deferred.bindingRule(), "R-LOD-09: named, never generic");
    assertEquals(DISPATCHER, replanned.run().deferredBy().get(c.orderId()));

    PlanningRun.Replanned alone =
        withChilledTrip().replanTrip(UUID.randomUUID(), 3, "R1", 1, List.of(), "no fleet", DISPATCHER, REGISTRY, CONTEXT);
    assertEquals(Optional.of("R-LOD-09"), alone.run().decisionFor(c.orderId()).orElseThrow().bindingRule());
  }

  @Test
  void theOptionsForAnOrderAreEveryPlaceWithItsChecks() {
    List<PlanningRun.Placement> options = draft().options(c, List.of(t1, t2, r1), REGISTRY, CONTEXT);

    assertTrue(options.stream().anyMatch(p -> p.vehicleId().equals("R1") && p.feasible()));
    assertTrue(options.stream()
        .filter(p -> p.vehicleId().equals("T2"))
        .allMatch(p -> !p.feasible() && p.checks().stream().anyMatch(r -> r.ruleId().equals("R-PLN-02") && !r.passed())));
    assertEquals(2, options.stream().filter(p -> p.vehicleId().equals("T1")).count(), "join trip 1, or open trip 2");
  }

  private static OrderDecision served(PlanOrder o, String vehicle) {
    return new OrderDecision(o.orderId(), AllocationDecision.SERVED, Optional.of(vehicle), Optional.of(1),
        Optional.empty(), "served on " + vehicle + " trip 1", List.of());
  }
}
