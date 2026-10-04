package com.waypoint.dispatch.notification.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.EtaChanged;
import com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.notification.NotificationSupport;
import com.waypoint.dispatch.notification.domain.Delivery.PushResult;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderDeferred;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.planning.contract.PlanEvents.StoreContacted;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseOrderStatusChanged;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Events in, notifications and deliveries out, the way the relay delivers them;
 * and the push job against a push service that answers as told.
 */
class NotificationConsumersIntegrationTest extends NotificationSupport {
  @Autowired PushDeliveryJob pushJob;
  @Autowired MeterRegistry meters;

  private OrderDeferred deferred(OutletView at) {
    return new OrderDeferred(
        UUID.randomUUID(), UUID.randomUUID(), at.outletId(), LocalDate.of(2026, 10, 3), "R-PLN-07",
        "vehicle capacity", 1);
  }

  @Test
  void aDeferralReachesTheStoreOnceInTheInboxWithItsReason() {
    UUID eventId = deliver("notification.on-order-deferred", deferred(outlet));

    Map<String, Object> mine = notificationFor(eventId, manager);
    assertFalse(recipientsOf(eventId).contains(stranger.id()), "another outlet's manager");
    assertFalse(recipientsOf(eventId).contains(dispatcher.id()), "no depot rule for a deferral");
    assertEquals("Order deferred", mine.get("title"));
    assertEquals("Your order planned for 2026-10-03 was deferred: vehicle capacity", mine.get("body"));
    List<Map<String, Object>> deliveries = deliveriesOf((UUID) mine.get("notification_id"));
    assertEquals(1, deliveries.size(), "no device subscribed, so the inbox is the only channel");
    assertEquals("in_app", deliveries.get(0).get("channel"));
    assertEquals("delivered", deliveries.get(0).get("status"));
  }

  @Test
  void aReplayedEventWritesNothingNew() {
    OrderDeferred event = deferred(outlet);
    UUID eventId = deliver("notification.on-order-deferred", event);
    int written = notificationsOf(eventId).size();
    notificationFor(eventId, manager);
    redeliver("notification.on-order-deferred", eventId, event, Optional.empty());

    // Past the consumer inbox too: applying the same event twice is still harmless.
    @SuppressWarnings("unchecked")
    EventSubscriber<OrderDeferred> consumer =
        (EventSubscriber<OrderDeferred>)
            subscribers.stream()
                .filter(s -> s.consumerName().equals("notification.on-order-deferred"))
                .findFirst()
                .orElseThrow();
    database.asSystem(
        consumer.moduleRole(),
        () ->
            consumer.on(
                new EventEnvelope<>(eventId, event.type(), 1, Instant.now(), "test", Optional.empty(),
                    Optional.empty(), event)));

    assertEquals(written, notificationsOf(eventId).size());
  }

  @Test
  void whoeverCausedTheEventIsNotTold() {
    UUID eventId = deliver("notification.on-order-deferred", deferred(outlet), manager.id());

    assertFalse(recipientsOf(eventId).contains(manager.id()));
  }

  @Test
  void anOutletNobodyManagesIsCountedAndDoesNotFail() {
    OutletView unmanaged =
        reference.outletsOfDepot(otherDepot, null).stream()
            .filter(o -> !o.outletId().equals(otherOutlet.outletId()))
            .reduce((a, b) -> b)
            .orElseThrow();
    double before = unrouted("order.deferred", "store_manager");

    UUID eventId = deliver("notification.on-order-deferred", deferred(unmanaged));

    // Another test run may have given the outlet a manager; what matters is that
    // the event was consumed either way, and counted when nobody was there.
    if (notificationsOf(eventId).isEmpty()) {
      assertEquals(before + 1, unrouted("order.deferred", "store_manager"));
    }
  }

  @Test
  void aLateArrivalReachesTheStoreAndTheDepotsDispatcherOnly() {
    UUID eventId =
        deliver(
            "notification.on-eta-changed",
            new EtaChanged(UUID.randomUUID(), UUID.randomUUID(), outlet.outletId(),
                Instant.parse("2026-10-02T05:10:00Z"), 25));

    List<UUID> told = recipientsOf(eventId);
    assertTrue(told.contains(manager.id()));
    assertTrue(told.contains(dispatcher.id()), "the depot was looked up from the outlet (R-EXE-15)");
    assertFalse(told.contains(farDispatcher.id()));
    assertFalse(told.contains(stranger.id()));
    assertTrue(
        notificationsOf(eventId).stream().anyMatch(n -> ((String) n.get("body")).contains("10:40")),
        "times are shown in the depot's zone");
  }

  @Test
  void aStockShortfallFromTheWarehouseReachesTheStoreButShippingDoesNot() {
    Order order = deliveredOrder(outlet);

    UUID shortId =
        deliver("notification.on-warehouse-order-status-changed",
            new WarehouseOrderStatusChanged(order.orderId(), Optional.empty(), "insufficient", Optional.empty()));
    UUID shippedId =
        deliver("notification.on-warehouse-order-status-changed",
            new WarehouseOrderStatusChanged(order.orderId(), Optional.empty(), "shipped", Optional.empty()));

    assertTrue(recipientsOf(shortId).contains(manager.id()));
    assertFalse(recipientsOf(shortId).contains(stranger.id()));
    assertTrue(recipientsOf(shippedId).isEmpty());
  }

  @Test
  void tomorrowsPlanReachesTomorrowsDriverAndTheLoaders() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    UUID tripId = UUID.randomUUID();

    UUID eventId =
        deliver(
            "notification.on-plan-published",
            new PlanPublished(
                UUID.randomUUID(), depot, date, 1, Optional.empty(),
                List.of(
                    new PlannedTrip(tripId, vehicle, 1, "B", "Colombo", "ambient", LocalTime.of(6, 30),
                        List.of(new PlannedStop(1, UUID.randomUUID(), outlet.outletId(), LocalTime.of(8, 0)))))));

    List<Map<String, Object>> written = notificationsOf(eventId);
    assertTrue(recipientsOf(eventId).containsAll(Set.of(driver.id(), loader.id())));
    Map<String, Object> toDriver =
        written.stream().filter(n -> n.get("recipient_user_id").equals(driver.id())).findFirst().orElseThrow();
    assertEquals("trip:" + tripId, toDriver.get("target_key"));
    assertEquals("Trip 1 on " + vehicle + ", departing 06:30, 1 stops.", toDriver.get("body"));
  }

  @Test
  void aPublishedPlanTellsEachStoreItsStopAndPlannedArrivalOnce() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    UUID tripId = UUID.randomUUID();
    PlanPublished published =
        new PlanPublished(
            UUID.randomUUID(), depot, date, 1, Optional.empty(),
            List.of(
                new PlannedTrip(tripId, vehicle, 1, "B", "Colombo", "ambient", LocalTime.of(5, 0),
                    List.of(
                        new PlannedStop(1, UUID.randomUUID(), otherOutlet.outletId(), LocalTime.of(5, 40)),
                        new PlannedStop(3, UUID.randomUUID(), outlet.outletId(), LocalTime.of(6, 10))))));

    UUID eventId = deliver("notification.on-plan-published", published);

    Map<String, Object> toManager = notificationFor(eventId, manager);
    assertTrue(((String) toManager.get("title")).startsWith("Delivery planned for "), "R-NOT-15");
    assertEquals("Your order is stop 3, planned arrival 06:10.", toManager.get("body"));
    assertEquals("stop:" + tripId + ":3", toManager.get("target_key"));
    assertEquals("Your order is stop 1, planned arrival 05:40.", notificationFor(eventId, stranger).get("body"),
        "each outlet hears its own stop");
    assertEquals(1, notificationsOf(eventId).stream().filter(n -> n.get("recipient_user_id").equals(manager.id())).count(),
        "one notice per store");
  }

  // ---- a revision tells only what it changed (R-NOT-12) ----------------------------

  private PlannedTrip tripOn(UUID tripId, String vehicle, int number, String outletId) {
    return new PlannedTrip(
        tripId, vehicle, number, "B", "Colombo", "ambient", LocalTime.of(6, 30),
        List.of(new PlannedStop(1, UUID.randomUUID(), outletId, LocalTime.of(8, 0))));
  }

  @Test
  void aRevisionTellsOnlyTheDriversOfTripsThatChangedAndTheOutletsItReaches() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    UUID changed = UUID.randomUUID();
    UUID untouched = UUID.randomUUID();
    String otherVehicle =
        reference.vehiclesOfDepot(depot, null).stream()
            .map(v -> v.vehicleId())
            .filter(id -> !id.equals(vehicle))
            .findFirst()
            .orElseThrow();

    UUID eventId =
        deliver(
            "notification.on-plan-revised",
            new PlanRevised(
                UUID.randomUUID(), depot, date, 2, UUID.randomUUID(), "vehicle fault",
                List.of(tripOn(changed, vehicle, 1, outlet.outletId()), tripOn(untouched, otherVehicle, 1, otherOutlet.outletId())),
                Optional.of(List.of(changed)),
                Optional.of(List.of(outlet.outletId()))));

    List<Map<String, Object>> written = notificationsOf(eventId);
    assertTrue(written.stream().anyMatch(n -> n.get("target_key").equals("trip:" + changed)), "the changed trip's driver");
    assertTrue(written.stream().noneMatch(n -> n.get("target_key").equals("trip:" + untouched)), "an untouched trip is not news");
    assertTrue(recipientsOf(eventId).contains(manager.id()), "the outlet it now reaches differently");
    assertFalse(recipientsOf(eventId).contains(stranger.id()), "an outlet the revision did not touch");
    assertEquals("Your delivery plan changed", notificationFor(eventId, manager).get("title"));
    assertTrue(recipientsOf(eventId).contains(loader.id()), "the depot's loaders work from the plan");
  }

  @Test
  void aRevisionThatChangedNoTripTellsNobodyOnTheRoadOrTheDock() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);

    UUID eventId =
        deliver(
            "notification.on-plan-revised",
            new PlanRevised(
                UUID.randomUUID(), depot, date, 2, UUID.randomUUID(), "an order was deferred",
                List.of(tripOn(UUID.randomUUID(), vehicle, 1, outlet.outletId())),
                Optional.of(List.of()),
                Optional.of(List.of())));

    assertTrue(recipientsOf(eventId).isEmpty(), "nothing a driver, a loader or a store would see differently");
  }

  @Test
  void aRevisionWrittenBeforeChangesWereRecordedStillTellsEveryTrip() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    UUID tripId = UUID.randomUUID();

    UUID eventId =
        deliver(
            "notification.on-plan-revised",
            new PlanRevised(
                UUID.randomUUID(), depot, date, 2, UUID.randomUUID(), "vehicle fault",
                List.of(tripOn(tripId, vehicle, 1, outlet.outletId()))));

    assertTrue(recipientsOf(eventId).containsAll(Set.of(driver.id(), loader.id())), "as it always did");
    assertFalse(recipientsOf(eventId).contains(manager.id()), "no outlet is named, so none is told separately");
  }

  @Test
  void aDispatchersMessageReachesTheOutletsStoreManagerInTheirOwnWords() {
    UUID orderId = UUID.randomUUID();

    UUID eventId =
        deliver(
            "notification.on-store-contacted",
            new StoreContacted(
                orderId, UUID.randomUUID(), outlet.outletId(), LocalDate.of(2026, 10, 3),
                "Please confirm you can take this on Wednesday", UUID.randomUUID()));

    Map<String, Object> mine = notificationFor(eventId, manager);
    assertEquals("Message from the dispatcher", mine.get("title"));
    assertEquals("Please confirm you can take this on Wednesday", mine.get("body"));
    assertFalse(recipientsOf(eventId).contains(stranger.id()), "another outlet's manager");
  }

  @Test
  void aReleasedTripWithNoDriverTellsTheDispatcher() {
    LocalDate date = someFarDate();
    String vehicle =
        reference.vehiclesOfDepot(depot, null).stream().findFirst().orElseThrow().vehicleId();

    UUID eventId =
        deliver(
            "notification.on-trip-released",
            new TripReleased(UUID.randomUUID(), UUID.randomUUID(), 1, vehicle, depot, date,
                List.of(new ReleasedStop(1, UUID.randomUUID(), outlet.outletId(), LocalTime.of(8, 0)))));

    assertTrue(recipientsOf(eventId).contains(dispatcher.id()), "LOD-05");
    assertEquals("Released trip has no driver", notificationFor(eventId, dispatcher).get("title"));
  }

  @Test
  void aReleasedTripTellsTheOtherLoadersButNotTheOneWhoReleasedIt() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    UUID tripId = UUID.randomUUID();
    TripReleased released =
        new TripReleased(tripId, UUID.randomUUID(), 1, vehicle, depot, date,
            List.of(new ReleasedStop(1, UUID.randomUUID(), outlet.outletId(), LocalTime.of(8, 0))));

    UUID byThisLoader = deliver("notification.on-trip-released", released, loader.id());
    assertFalse(recipientsOf(byThisLoader).contains(loader.id()), "R-NOT-07: the releaser is not told");

    UUID bySomeoneElse =
        deliver("notification.on-trip-released",
            new TripReleased(UUID.randomUUID(), UUID.randomUUID(), 1, vehicle, depot, date, released.stops()));
    Map<String, Object> toLoader = notificationFor(bySomeoneElse, loader);
    assertEquals("Trip released · " + vehicle, toLoader.get("title"));
    assertFalse(recipientsOf(bySomeoneElse).contains(dispatcher.id()), "a driver was reached, so no LOD-05 fallback");
  }

  @Test
  void aReleasedTripTellsEachOutletItsStopAndExpectedArrival() {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    UUID tripId = UUID.randomUUID();

    UUID eventId =
        deliver(
            "notification.on-trip-released",
            new TripReleased(tripId, UUID.randomUUID(), 1, vehicle, depot, date,
                List.of(
                    new ReleasedStop(1, UUID.randomUUID(), otherOutlet.outletId(), LocalTime.of(7, 15)),
                    new ReleasedStop(2, UUID.randomUUID(), outlet.outletId(), LocalTime.of(8, 5)))));

    Map<String, Object> toManager = notificationFor(eventId, manager);
    assertEquals(vehicle + " is on the way", toManager.get("title"));
    assertEquals("You're stop 2 of 2. Expected 08:05.", toManager.get("body"));
    assertEquals("stop:2:" + outlet.outletId(), toManager.get("target_key"), "one per stop");
    assertEquals("You're stop 1 of 2. Expected 07:15.", notificationFor(eventId, stranger).get("body"),
        "each outlet hears its own stop");
  }

  @Test
  void theInboxCarriesTheFactsAMessageWasFilledFromSoAClientCanTranslateIt() throws Exception {
    LocalDate date = someFarDate();
    String vehicle = assignOn(driver, date);
    deliver(
        "notification.on-trip-released",
        new TripReleased(UUID.randomUUID(), UUID.randomUUID(), 1, vehicle, depot, date,
            List.of(new ReleasedStop(4, UUID.randomUUID(), outlet.outletId(), LocalTime.of(9, 20)))));

    com.fasterxml.jackson.databind.JsonNode newest = read(manager, "/api/notifications?limit=1", 200).get("items").get(0);
    assertEquals(vehicle + " is on the way", newest.get("title").asText());
    assertEquals("4", newest.get("facts").get("stopNumber").asText());
    assertEquals("09:20", newest.get("facts").get("plannedArrival").asText());
    assertEquals(vehicle, newest.get("facts").get("vehicleId").asText());
  }

  // ---- push ---------------------------------------------------------------------------

  @Test
  void aPushIsQueuedForEachDeviceAndSentAfterCommit() throws Exception {
    UUID subscription = subscribe(manager, endpoint());

    UUID eventId = deliver("notification.on-order-deferred", deferred(outlet));
    UUID notificationId = notificationIdFor(eventId, manager);
    Map<String, Object> queued = push(notificationId);
    assertEquals("pending", queued.get("status"), "nothing leaves the process inside the consumer (R-NOT-05)");
    assertEquals(subscription, queued.get("subscription_id"));

    attempt(notificationId, Instant.now().plusSeconds(1));

    assertEquals("sent", push(notificationId).get("status"));
  }

  @Test
  void aFailingPushRetriesThenReachesDeadLetter() throws Exception {
    subscribe(manager, endpoint());
    push.answer = PushResult.RETRYABLE;
    UUID notificationId =
        notificationIdFor(deliver("notification.on-order-deferred", deferred(outlet)), manager);

    Instant at = Instant.now();
    for (int run = 1; run <= 6; run++) {
      at = at.plus(Duration.ofDays(1));
      attempt(notificationId, at);
      Map<String, Object> d = push(notificationId);
      assertEquals(run, ((Number) d.get("attempts")).intValue());
      assertEquals(run < 6 ? "failed" : "dead", d.get("status"), "after run " + run);
    }
    assertEquals("fake RETRYABLE", push(notificationId).get("last_error"), "a dead push keeps why");

    pushJob.runAt(at.plus(Duration.ofDays(1)));
    assertEquals(6, ((Number) push(notificationId).get("attempts")).intValue(), "dead is never retried");
  }

  @Test
  void aSubscriptionThePushServiceSaysIsGoneIsExpiredAndNotUsedAgain() throws Exception {
    UUID subscription = subscribe(manager, endpoint());
    push.answer = PushResult.GONE;
    UUID first =
        notificationIdFor(deliver("notification.on-order-deferred", deferred(outlet)), manager);

    attempt(first, Instant.now().plusSeconds(1));

    assertEquals("dead", push(first).get("status"));
    assertEquals(1, ((Number) push(first).get("attempts")).intValue(), "NOT-01: not retried");
    assertEquals("expired", subscriptionStatus(subscription));
    UUID second =
        notificationIdFor(deliver("notification.on-order-deferred", deferred(outlet)), manager);
    assertTrue(
        deliveriesOf(second).stream().noneMatch(d -> d.get("channel").equals("push")),
        "an expired subscription gets nothing new");
  }

  @Test
  void withPushOffTheInboxStillWorksAndNoPushIsQueued() throws Exception {
    subscribe(manager, endpoint());
    push.enabled = false;

    UUID eventId = deliver("notification.on-order-deferred", deferred(outlet));

    UUID notificationId = notificationIdFor(eventId, manager);
    assertEquals(List.of("in_app"), deliveriesOf(notificationId).stream().map(d -> d.get("channel")).toList());
    assertEquals(
        "push is not configured on this server",
        read(manager, "/api/notifications/push-config", 200).get("reason").asText());
  }

  /**
   * Runs the push job until it has tried this push once more. The shared test
   * database holds other people's due pushes too, and one run takes a batch.
   */
  private void attempt(UUID notificationId, Instant at) {
    int before = ((Number) push(notificationId).get("attempts")).intValue();
    for (int run = 0; run < 20 && ((Number) push(notificationId).get("attempts")).intValue() == before; run++) {
      pushJob.runAt(at);
    }
  }

  private Map<String, Object> push(UUID notificationId) {
    return deliveriesOf(notificationId).stream()
        .filter(d -> d.get("channel").equals("push"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no push delivery for " + notificationId));
  }

  private double unrouted(String event, String role) {
    Counter c = meters.find("waypoint.notification.unrouted").tags("event", event, "role", role).counter();
    return c == null ? 0 : c.count();
  }
}
