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
    assertTrue(refused.violations().contains("R-PLN-02"), refused.violations().toString());
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
            d.supersedes(), d.demandFingerprint(), false, false, d.engine(), d.days(), d.decisions(),
            d.deferredBy(), 2);
    DomainException refused =
        assertThrows(
            DomainException.class,
            () -> published.defer(UUID.randomUUID(), 2, a.orderId(), "late change", DISPATCHER, REGISTRY, CONTEXT));
    assertEquals(ErrorCode.CONFLICT, refused.code());
    assertEquals(List.of("R-PLN-28"), refused.violations());
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

  private static OrderDecision served(PlanOrder o, String vehicle) {
    return new OrderDecision(o.orderId(), AllocationDecision.SERVED, Optional.of(vehicle), Optional.of(1),
        Optional.empty(), "served on " + vehicle + " trip 1", List.of());
  }
}
