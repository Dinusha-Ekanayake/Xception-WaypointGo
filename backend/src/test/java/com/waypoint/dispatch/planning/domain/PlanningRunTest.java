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
import com.waypoint.dispatch.planning.domain.PlanningRun.Mark;
import com.waypoint.dispatch.planning.domain.PlanningRun.Source;
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

    PlanningRun next = before.override(nextId, 2, b, t2, 1, "closer to the outlet", DISPATCHER, REGISTRY, CONTEXT);

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
            () -> draft().override(UUID.randomUUID(), 2, c, t2, 1, "dispatcher insists", DISPATCHER, REGISTRY, CONTEXT));
    assertEquals(ErrorCode.CONSTRAINT_VIOLATED, refused.code());
    assertTrue(refused.rules().contains("R-PLN-02"), refused.rules().toString());
  }

  @Test
  void anOverrideToATripThatCannotExistIsInvalid() {
    DomainException invalid =
        assertThrows(
            DomainException.class,
            () -> draft().override(UUID.randomUUID(), 2, a, t1, 3, "third trip", DISPATCHER, REGISTRY, CONTEXT));
    assertEquals(ErrorCode.VALIDATION_FAILED, invalid.code());
  }

  @Test
  void overridingADeferredOrderServesItAndClearsTheDeferral() {
    PlanningRun before = draft();
    assertEquals(ENGINE, before.deferredBy().get(c.orderId()));

    PlanningRun next = before.override(UUID.randomUUID(), 2, c, r1, 1, "reefer freed up", DISPATCHER, REGISTRY, CONTEXT);

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
  void anOverrideAndADeferralNameTheDispatcherAndSurviveTheNextEdit() {
    PlanningRun placed = draft().override(UUID.randomUUID(), 2, c, r1, 1, "reefer freed up", DISPATCHER, REGISTRY, CONTEXT);
    assertEquals(Optional.of(new Mark(Source.OVERRIDE, DISPATCHER, false)), placed.markOf(c.orderId()));
    assertTrue(placed.markOf(a.orderId()).isEmpty(), "an order nobody touched is the engine's");

    UUID other = UUID.randomUUID();
    PlanningRun deferred = placed.defer(UUID.randomUUID(), 3, a.orderId(), "stocktake", other, REGISTRY, CONTEXT);
    assertEquals(Optional.of(new Mark(Source.MANUAL_DEFER, other, false)), deferred.markOf(a.orderId()));
    assertEquals(
        Optional.of(new Mark(Source.OVERRIDE, DISPATCHER, false)),
        deferred.markOf(c.orderId()),
        "an earlier hand decision carries through a later edit");
  }

  @Test
  void aMarkDropsWhenTheOrderIsDecidedTheOtherWay() {
    PlanningRun deferred = draft().defer(UUID.randomUUID(), 2, a.orderId(), "stocktake", DISPATCHER, REGISTRY, CONTEXT);
    assertEquals(Source.MANUAL_DEFER, deferred.markOf(a.orderId()).orElseThrow().source());

    PlanningRun back = deferred.override(UUID.randomUUID(), 3, a, t1, 1, "reopened", DISPATCHER, REGISTRY, CONTEXT);

    assertEquals(Source.OVERRIDE, back.markOf(a.orderId()).orElseThrow().source(), "placing it replaces the deferral");
    assertFalse(back.deferredBy().containsKey(a.orderId()));
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
            d.supersedes(), d.revisionReason(), d.demandFingerprint(), false, false, d.engine(), d.improvement(), d.cost(), d.days(), d.decisions(),
            d.deferredBy(), d.marks(), 2);
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
            () -> draft().override(UUID.randomUUID(), 2, stranger, t2, 1, "who is this", DISPATCHER, REGISTRY, CONTEXT));
    assertEquals(ErrorCode.NOT_FOUND, missing.code());
    assertNotEquals(stranger.orderId(), a.orderId());
  }

  // ---- revisions and replanning (step 6) -------------------------------------

  PlanningRun published(PlanningRun d) {
    return new PlanningRun(
        d.planId(), d.depotCode(), d.serviceDate(), d.planVersion(), PlanStatus.PUBLISHED, d.stamps(),
        d.supersedes(), d.revisionReason(), d.demandFingerprint(), false, false, d.engine(), d.improvement(), d.cost(), d.days(), d.decisions(),
        d.deferredBy(), d.marks(), 2);
  }

  /** c served alone on the reefer, a and b on truck T1. */
  PlanningRun withChilledTrip() {
    return draft().override(UUID.randomUUID(), 2, c, r1, 1, "reefer free", DISPATCHER, REGISTRY, CONTEXT);
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

  // ---- swap, keep, lock, reorder ---------------------------------------------

  final PlanOrder d = order().build();

  /** a and b on T1 trip 1; d, an ambient order of the same trip, deferred by the engine on time. */
  PlanningRun draftWithAmbientDeferred() {
    VehicleDay day = VehicleDay.idle(t1).withNewTrip(a).withJoined(1, b);
    AllocationResult result =
        new AllocationResult(
            List.of(day),
            List.of(
                served(a, "T1"),
                served(b, "T1"),
                new OrderDecision(d.orderId(), AllocationDecision.DEFERRED, Optional.empty(), Optional.empty(),
                    Optional.of("R-PLN-07"), "no trip had time", List.of())),
            false,
            "test");
    return PlanningRun.draft(
        UUID.randomUUID(), "Peliyagoda", LocalDate.of(2026, 10, 5), 1,
        new Stamps(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
        Optional.empty(), "fp", result, ENGINE);
  }

  Set<UUID> everyOrder() {
    return Set.of(a.orderId(), b.orderId(), d.orderId());
  }

  @Test
  void aSwapDefersTheServedOrderAndServesTheDeferredOneOnItsTrip() {
    PlanningRun swapped =
        draftWithAmbientDeferred()
            .swap(UUID.randomUUID(), 2, a, d, "outlet asked for the later delivery", DISPATCHER, REGISTRY, CONTEXT);

    OrderDecision in = swapped.decisionFor(d.orderId()).orElseThrow();
    assertEquals(AllocationDecision.SERVED, in.decision());
    assertEquals(Optional.of("T1"), in.vehicleId());
    assertEquals(Optional.of(1), in.tripNumber());
    OrderDecision out = swapped.decisionFor(a.orderId()).orElseThrow();
    assertEquals(AllocationDecision.DEFERRED, out.decision());
    assertEquals(Optional.of(PlanningRun.MANUAL_DEFERRAL_RULE), out.bindingRule());
    assertEquals(DISPATCHER, swapped.deferredBy().get(a.orderId()), "rule 8: who");
    assertFalse(swapped.deferredBy().containsKey(d.orderId()));
    assertEquals(Source.SWAP, swapped.markOf(d.orderId()).orElseThrow().source());
    assertEquals(Source.MANUAL_DEFER, swapped.markOf(a.orderId()).orElseThrow().source());
    assertTrue(swapped.verify(everyOrder(), CONTEXT, REGISTRY).isEmpty(), "the whole plan still passes");
  }

  @Test
  void aSwapCanFixTheTripsStopOrderInTheSameChange() {
    PlanningRun plan = draftWithAmbientDeferred();
    PlanningRun swapped =
        plan.swap(UUID.randomUUID(), 2, a, d, List.of(d.orderId(), b.orderId()), "the new outlet first", DISPATCHER, REGISTRY, CONTEXT);

    Trip trip = swapped.days().get(0).trip(1);
    assertEquals(List.of(d.orderId(), b.orderId()), trip.sequence());
    assertTrue(trip.hasFixedSequence());
    assertEquals(AllocationDecision.DEFERRED, swapped.decisionFor(a.orderId()).orElseThrow().decision());
    assertEquals(Source.SWAP, swapped.markOf(d.orderId()).orElseThrow().source());

    DomainException wrong =
        assertThrows(
            DomainException.class,
            () -> plan.swap(UUID.randomUUID(), 2, a, d, List.of(a.orderId(), b.orderId()), "names the outgoing one", DISPATCHER, REGISTRY, CONTEXT));
    assertEquals(ErrorCode.VALIDATION_FAILED, wrong.code());
    assertEquals(AllocationDecision.SERVED, plan.decisionFor(a.orderId()).orElseThrow().decision(), "nothing half done");
  }

  @Test
  void aSwapThatBreaksARuleIsRefusedWholeWithThatRule() {
    PlanningRun before = draft();
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> before.swap(UUID.randomUUID(), 2, a, c, "try the chilled one", DISPATCHER, REGISTRY, CONTEXT));
    assertEquals(ErrorCode.CONSTRAINT_VIOLATED, refused.code());
    assertTrue(refused.rules().contains("R-PLN-02"), refused.rules().toString());
    assertEquals(AllocationDecision.SERVED, before.decisionFor(a.orderId()).orElseThrow().decision(), "nothing half done");
  }

  @Test
  void aSwapNeedsAServedOrderOutAndADeferredOneIn() {
    PlanningRun plan = draftWithAmbientDeferred();
    assertEquals(
        ErrorCode.VALIDATION_FAILED,
        assertThrows(
                DomainException.class,
                () -> plan.swap(UUID.randomUUID(), 2, d, a, "backwards", DISPATCHER, REGISTRY, CONTEXT))
            .code());
    assertEquals(
        ErrorCode.VALIDATION_FAILED,
        assertThrows(
                DomainException.class,
                () -> plan.swap(UUID.randomUUID(), 2, a, b, "both served", DISPATCHER, REGISTRY, CONTEXT))
            .code());
  }

  @Test
  void keepingDeferredRecordsWhoDecidedAndWhyWithoutMovingAnything() {
    PlanningRun before = draft();
    PlanningRun kept =
        before.keepDeferred(
            UUID.randomUUID(), 2, List.of(c.orderId()), "no refrigerated vehicle tomorrow either", DISPATCHER, REGISTRY,
            CONTEXT);

    OrderDecision kept1 = kept.decisionFor(c.orderId()).orElseThrow();
    assertEquals(AllocationDecision.DEFERRED, kept1.decision());
    assertEquals(Optional.of("R-PLN-06"), kept1.bindingRule(), "the rule that stopped it is still the rule");
    assertTrue(
        kept1.reason().contains("Kept deferred by the dispatcher: no refrigerated vehicle tomorrow either"),
        kept1.reason());
    assertEquals(DISPATCHER, kept.deferredBy().get(c.orderId()));
    assertEquals(Source.KEPT, kept.markOf(c.orderId()).orElseThrow().source());
    assertEquals(before.days(), kept.days(), "no trip changed");
  }

  @Test
  void onlyDeferredOrdersCanBeKeptDeferred() {
    assertEquals(
        ErrorCode.VALIDATION_FAILED,
        assertThrows(
                DomainException.class,
                () -> draft().keepDeferred(UUID.randomUUID(), 2, List.of(a.orderId()), "no", DISPATCHER, REGISTRY, CONTEXT))
            .code());
    assertEquals(
        ErrorCode.VALIDATION_FAILED,
        assertThrows(
                DomainException.class,
                () -> draft().keepDeferred(UUID.randomUUID(), 2, List.of(), "none named", DISPATCHER, REGISTRY, CONTEXT))
            .code());
  }

  @Test
  void aLockHoldsAnOrderAndAnUnlockLetsItGo() {
    PlanningRun locked = draft().lock(UUID.randomUUID(), 2, a.orderId(), true, DISPATCHER, REGISTRY, CONTEXT);
    assertEquals(Optional.of(new Mark(Source.ENGINE, DISPATCHER, true)), locked.markOf(a.orderId()));

    PlanningRun free = locked.lock(UUID.randomUUID(), 3, a.orderId(), false, DISPATCHER, REGISTRY, CONTEXT);
    assertTrue(free.markOf(a.orderId()).isEmpty(), "an engine placement nobody holds is nobody's again");
  }

  @Test
  void aLockOnAHandPlacementKeepsWhoPlacedIt() {
    UUID placer = UUID.randomUUID();
    PlanningRun placed = draft().override(UUID.randomUUID(), 2, c, r1, 1, "reefer freed up", placer, REGISTRY, CONTEXT);
    PlanningRun locked = placed.lock(UUID.randomUUID(), 3, c.orderId(), true, DISPATCHER, REGISTRY, CONTEXT);
    assertEquals(Source.OVERRIDE, locked.markOf(c.orderId()).orElseThrow().source());
    assertTrue(locked.markOf(c.orderId()).orElseThrow().locked());

    PlanningRun unlocked = locked.lock(UUID.randomUUID(), 4, c.orderId(), false, DISPATCHER, REGISTRY, CONTEXT);
    assertEquals(Optional.of(new Mark(Source.OVERRIDE, DISPATCHER, false)), unlocked.markOf(c.orderId()));
  }

  @Test
  void onlyAnOrderOnATripCanBeLockedAndOnlyOnce() {
    assertEquals(
        ErrorCode.VALIDATION_FAILED,
        assertThrows(
                DomainException.class,
                () -> draft().lock(UUID.randomUUID(), 2, c.orderId(), true, DISPATCHER, REGISTRY, CONTEXT))
            .code());
    PlanningRun locked = draft().lock(UUID.randomUUID(), 2, a.orderId(), true, DISPATCHER, REGISTRY, CONTEXT);
    assertEquals(
        ErrorCode.VALIDATION_FAILED,
        assertThrows(
                DomainException.class,
                () -> locked.lock(UUID.randomUUID(), 3, a.orderId(), true, DISPATCHER, REGISTRY, CONTEXT))
            .code());
  }

  @Test
  void aLockFallsAwayWhenTheOrderIsTakenOffItsTrip() {
    PlanningRun locked = draft().lock(UUID.randomUUID(), 2, a.orderId(), true, DISPATCHER, REGISTRY, CONTEXT);
    PlanningRun deferred = locked.defer(UUID.randomUUID(), 3, a.orderId(), "stocktake", DISPATCHER, REGISTRY, CONTEXT);
    assertEquals(Source.MANUAL_DEFER, deferred.markOf(a.orderId()).orElseThrow().source());
    assertFalse(deferred.markOf(a.orderId()).orElseThrow().locked(), "a deferred order is held nowhere");
  }

  @Test
  void aDispatchersStopOrderIsTimedAsGivenAndJudgedLikeAnyOther() {
    PlanOrder early = order().window("03:00", "04:10").build();
    PlanOrder late = order().build();
    VehicleDay day = VehicleDay.idle(t1).withNewTrip(early).withJoined(1, late);
    AllocationResult result =
        new AllocationResult(List.of(day), List.of(served(early, "T1"), served(late, "T1")), false, "test");
    PlanningRun plan =
        PlanningRun.draft(
            UUID.randomUUID(), "Peliyagoda", LocalDate.of(2026, 10, 5), 1,
            new Stamps(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), Optional.empty(), "fp", result, ENGINE);

    PlanningRun reordered =
        plan.reorderStops(
            UUID.randomUUID(), 2, "T1", 1, List.of(early.orderId(), late.orderId()), "the early window first", REGISTRY,
            CONTEXT);
    Trip trip = reordered.days().get(0).trip(1);
    assertEquals(List.of(early.orderId(), late.orderId()), trip.sequence());
    assertTrue(trip.hasFixedSequence());
    assertTrue(reordered.decisionFor(late.orderId()).orElseThrow().reason().startsWith("stops reordered by dispatcher"));
    assertEquals(
        early.orderId(),
        TripTimeline.schedule(reordered.days().get(0), CONTEXT.travel(), CONTEXT.rules())
            .get(0).stops().get(0).order().orderId());

    DomainException refused =
        assertThrows(
            DomainException.class,
            () ->
                plan.reorderStops(
                    UUID.randomUUID(), 2, "T1", 1, List.of(late.orderId(), early.orderId()), "late one first", REGISTRY,
                    CONTEXT));
    assertEquals(ErrorCode.CONSTRAINT_VIOLATED, refused.code());
    assertTrue(
        refused.rules().stream().anyMatch(r -> r.equals("R-PLN-13") || r.equals("R-PLN-29")),
        refused.rules().toString());
  }

  @Test
  void aStopOrderMustNameEveryOrderOfTheTripOnce() {
    PlanningRun plan = draft();
    assertEquals(
        ErrorCode.VALIDATION_FAILED,
        assertThrows(
                DomainException.class,
                () -> plan.reorderStops(UUID.randomUUID(), 2, "T1", 1, List.of(a.orderId()), "short", REGISTRY, CONTEXT))
            .code());
    assertEquals(
        ErrorCode.NOT_FOUND,
        assertThrows(
                DomainException.class,
                () ->
                    plan.reorderStops(
                        UUID.randomUUID(), 2, "T1", 2, List.of(a.orderId(), b.orderId()), "no trip 2", REGISTRY, CONTEXT))
            .code());
  }

  private static OrderDecision served(PlanOrder o, String vehicle) {
    return new OrderDecision(o.orderId(), AllocationDecision.SERVED, Optional.of(vehicle), Optional.of(1),
        Optional.empty(), "served on " + vehicle + " trip 1", List.of());
  }
}
