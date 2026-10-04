package com.waypoint.dispatch.notification.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.notification.domain.NotificationPolicy.Addressed;
import com.waypoint.dispatch.notification.domain.NotificationPolicy.Recipients;
import com.waypoint.dispatch.notification.domain.NotificationPolicy.Routing;
import com.waypoint.dispatch.notification.domain.RoutedEvent.Subject;
import com.waypoint.dispatch.notification.domain.RoutedEvent.Target;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The routing matrix applied to events, with Identity's answers given in place of a database. */
class NotificationPolicyTest {
  private static final UUID MANAGER = UUID.randomUUID();
  private static final UUID DISPATCHER = UUID.randomUUID();
  private static final UUID LOADER = UUID.randomUUID();
  private static final UUID DRIVER = UUID.randomUUID();
  private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 3);

  private static final RoutingTable TABLE =
      new RoutingTable(
          1,
          List.of(
              RoutingRule.of("order.deferred", "store_manager", ScopeKind.OUTLET, true,
                  "Order deferred", "Your order planned for {serviceDate} was deferred: {reason}"),
              RoutingRule.of("eta.changed", "store_manager", ScopeKind.OUTLET, true,
                  "Arrival time changed", "Now expected at {expectedArrival}"),
              RoutingRule.of("eta.changed", "dispatcher", ScopeKind.DEPOT, true,
                  "Delivery running late", "{outletId} now expected at {expectedArrival}"),
              RoutingRule.of("plan.published", "loader", ScopeKind.DEPOT, true,
                  "Plan published for {serviceDate}", "{tripCount} trips"),
              RoutingRule.of("plan.published", "driver", ScopeKind.VEHICLE, true,
                  "Your trip for {serviceDate}", "Trip {tripNumber}"),
              RoutingRule.of("trip.released", "driver", ScopeKind.VEHICLE, true,
                  "Your vehicle is loaded", "{vehicleId}"),
              new RoutingRule("trip.released", "dispatcher", ScopeKind.DEPOT, true, Optional.empty(), Set.of(),
                  Optional.of("driver"), "Released trip has no driver", "{vehicleId} has no driver"),
              new RoutingRule("warehouse.order_status_changed", "store_manager", ScopeKind.OUTLET, true,
                  Optional.of("status"), Set.of("insufficient", "expired"), Optional.empty(),
                  "Stock problem", "Reported as {status}"),
              RoutingRule.of("delivery.started", "driver", ScopeKind.VEHICLE, true, "Started", "Started")));

  /** Who holds what, as Identity would answer. */
  private final Map<String, List<UUID>> holders = new HashMap<>();
  private final List<Optional<LocalDate>> datesAsked = new java.util.ArrayList<>();

  private final Recipients identity =
      (role, scope, id, on) -> {
        datesAsked.add(on);
        return holders.getOrDefault(role + "@" + scope.code() + ":" + id, List.of());
      };

  NotificationPolicyTest() {
    holders.put("store_manager@outlet:OUT001", List.of(MANAGER));
    holders.put("dispatcher@depot:D1", List.of(DISPATCHER));
    holders.put("loader@depot:D1", List.of(LOADER));
  }

  private static RoutedEvent event(String type, Map<String, String> facts, Target... targets) {
    return new RoutedEvent(UUID.randomUUID(), type, Optional.empty(), Optional.empty(), facts, List.of(targets));
  }

  private static Target outlet(String id) {
    return Target.of(ScopeKind.OUTLET, id, Optional.of(new Subject("order", "o-1")));
  }

  private static Target depot(String id) {
    return Target.of(ScopeKind.DEPOT, id, Optional.empty());
  }

  @Test
  void aDeferralReachesTheOutletsManagerWithTheBindingReason() {
    Routing r =
        NotificationPolicy.route(
            event("order.deferred", Map.of("serviceDate", "2026-10-03", "reason", "vehicle capacity"),
                outlet("OUT001")),
            TABLE, identity);

    assertEquals(1, r.notifications().size());
    Addressed a = r.notifications().get(0);
    assertEquals(MANAGER, a.recipient());
    assertEquals("Order deferred", a.title());
    assertEquals("Your order planned for 2026-10-03 was deferred: vehicle capacity", a.body());
    assertTrue(a.push());
    assertEquals(Optional.of(new Subject("order", "o-1")), a.target().subject());
    assertTrue(a.missingFacts().isEmpty());
  }

  @Test
  void oneEventReachesEachRoleAtItsOwnScope() {
    Routing r =
        NotificationPolicy.route(
            event("eta.changed", Map.of("expectedArrival", "10:40", "outletId", "OUT001"),
                outlet("OUT001"), depot("D1")),
            TABLE, identity);

    assertEquals(Set.of(MANAGER, DISPATCHER), Set.copyOf(r.notifications().stream().map(Addressed::recipient).toList()));
    assertEquals(
        "OUT001 now expected at 10:40",
        r.notifications().stream().filter(a -> a.recipient().equals(DISPATCHER)).findFirst().orElseThrow().body());
  }

  @Test
  void theActorIsNotToldAboutTheirOwnAction() {
    RoutedEvent own =
        new RoutedEvent(UUID.randomUUID(), "order.deferred", Optional.of(MANAGER), Optional.empty(),
            Map.of("serviceDate", "x", "reason", "y"), List.of(outlet("OUT001")));

    Routing r = NotificationPolicy.route(own, TABLE, identity);

    assertTrue(r.notifications().isEmpty());
    assertTrue(r.unrouted().isEmpty(), "someone holds the scope; they were only the actor");
  }

  @Test
  void aScopeNobodyHoldsIsReportedNotThrown() {
    Routing r =
        NotificationPolicy.route(
            event("order.deferred", Map.of("serviceDate", "x", "reason", "y"), outlet("OUT999")), TABLE, identity);

    assertTrue(r.notifications().isEmpty());
    assertEquals(List.of(new NotificationPolicy.Unrouted("store_manager", ScopeKind.OUTLET, "OUT999")), r.unrouted());
  }

  @Test
  void eachTripReachesItsDriverOnTheServiceDate() {
    holders.put("driver@vehicle:V1", List.of(DRIVER));
    RoutedEvent plan =
        new RoutedEvent(
            UUID.randomUUID(), "plan.published", Optional.empty(), Optional.of(TOMORROW),
            Map.of("serviceDate", "2026-10-03", "tripCount", "2"),
            List.of(
                depot("D1"),
                new Target(ScopeKind.VEHICLE, "V1", "trip:t1", Map.of("tripNumber", "1"), Optional.empty()),
                new Target(ScopeKind.VEHICLE, "V1", "trip:t2", Map.of("tripNumber", "2"), Optional.empty())));

    Routing r = NotificationPolicy.route(plan, TABLE, identity);

    List<Addressed> toDriver = r.notifications().stream().filter(a -> a.recipient().equals(DRIVER)).toList();
    assertEquals(List.of("Trip 1", "Trip 2"), toDriver.stream().map(Addressed::body).toList());
    assertTrue(toDriver.stream().allMatch(Addressed::push), "a plan is trip-level, so a driver is pushed it");
    assertTrue(r.notifications().stream().anyMatch(a -> a.recipient().equals(LOADER)));
    assertTrue(datesAsked.stream().allMatch(d -> d.equals(Optional.of(TOMORROW))));
  }

  @Test
  void aDriverIsNeverPushedAnEventThatIsNotTripLevel() {
    holders.put("driver@vehicle:V1", List.of(DRIVER));

    Routing r =
        NotificationPolicy.route(
            event("delivery.started", Map.of(), Target.of(ScopeKind.VEHICLE, "V1", Optional.empty())), TABLE,
            identity);

    assertEquals(1, r.notifications().size());
    assertFalse(r.notifications().get(0).push(), "R-NOT-08: inbox only while driving");
  }

  @Test
  void aReleasedTripWithNoDriverGoesToTheDispatcherInstead() {
    Routing r =
        NotificationPolicy.route(
            event("trip.released", Map.of("vehicleId", "V7"),
                Target.of(ScopeKind.VEHICLE, "V7", Optional.empty()), depot("D1")),
            TABLE, identity);

    assertEquals(List.of(DISPATCHER), r.notifications().stream().map(Addressed::recipient).toList());
    assertEquals("V7 has no driver", r.notifications().get(0).body());
  }

  @Test
  void aReleasedTripWithADriverDoesNotDisturbTheDispatcher() {
    holders.put("driver@vehicle:V7", List.of(DRIVER));

    Routing r =
        NotificationPolicy.route(
            event("trip.released", Map.of("vehicleId", "V7"),
                Target.of(ScopeKind.VEHICLE, "V7", Optional.empty()), depot("D1")),
            TABLE, identity);

    assertEquals(List.of(DRIVER), r.notifications().stream().map(Addressed::recipient).toList());
  }

  @Test
  void aConditionOnAFactSelectsOnlyTheStatusesItNames() {
    Routing shipped =
        NotificationPolicy.route(
            event("warehouse.order_status_changed", Map.of("status", "shipped"), outlet("OUT001")), TABLE, identity);
    Routing short_ =
        NotificationPolicy.route(
            event("warehouse.order_status_changed", Map.of("status", "insufficient"), outlet("OUT001")), TABLE,
            identity);

    assertTrue(shipped.notifications().isEmpty());
    assertEquals("Reported as insufficient", short_.notifications().get(0).body());
  }

  @Test
  void onePersonHearsAboutOneTargetOnceEvenIfListedTwice() {
    holders.put("store_manager@outlet:OUT001", List.of(MANAGER, MANAGER));

    Routing r =
        NotificationPolicy.route(
            event("order.deferred", Map.of("serviceDate", "x", "reason", "y"), outlet("OUT001")), TABLE, identity);

    assertEquals(1, r.notifications().size());
  }

  @Test
  void aFactTheEventLacksIsReportedAndRenderedAsADash() {
    Routing r =
        NotificationPolicy.route(event("order.deferred", Map.of("reason", "y"), outlet("OUT001")), TABLE, identity);

    assertEquals("Your order planned for - was deferred: y", r.notifications().get(0).body());
    assertEquals(Set.of("serviceDate"), r.notifications().get(0).missingFacts());
  }

  @Test
  void anEventNoRuleNamesReachesNobody() {
    Routing r = NotificationPolicy.route(event("order.placed", Map.of(), outlet("OUT001")), TABLE, identity);

    assertTrue(r.notifications().isEmpty());
    assertTrue(r.unrouted().isEmpty());
  }
}
