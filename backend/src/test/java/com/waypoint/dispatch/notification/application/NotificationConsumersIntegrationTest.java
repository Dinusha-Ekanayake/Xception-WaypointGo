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

    List<Map<String, Object>> written = notificationsOf(eventId);
    assertEquals(List.of(manager.id()), recipientsOf(eventId));
    assertEquals("Order deferred", written.get(0).get("title"));
    assertEquals("Your order planned for 2026-10-03 was deferred: vehicle capacity", written.get(0).get("body"));
    List<Map<String, Object>> deliveries = deliveriesOf((UUID) written.get(0).get("notification_id"));
    assertEquals(1, deliveries.size(), "no device subscribed, so the inbox is the only channel");
    assertEquals("in_app", deliveries.get(0).get("channel"));
    assertEquals("delivered", deliveries.get(0).get("status"));
  }

  @Test
  void aReplayedEventWritesNothingNew() {
    OrderDeferred event = deferred(outlet);
    UUID eventId = deliver("notification.on-order-deferred", event);
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

    assertEquals(1, notificationsOf(eventId).size());
  }

  @Test
  void whoeverCausedTheEventIsNotTold() {
    UUID eventId = deliver("notification.on-order-deferred", deferred(outlet), manager.id());

    assertTrue(notificationsOf(eventId).isEmpty());
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

    assertEquals(List.of(manager.id()), recipientsOf(shortId));
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
  void aReleasedTripWithNoDriverTellsTheDispatcher() {
    LocalDate date = someFarDate();
    String vehicle =
        reference.vehiclesOfDepot(depot, null).stream().findFirst().orElseThrow().vehicleId();

    UUID eventId =
        deliver(
            "notification.on-trip-released",
            new TripReleased(UUID.randomUUID(), UUID.randomUUID(), 1, vehicle, depot, date,
                List.of(new ReleasedStop(1, UUID.randomUUID(), outlet.outletId(), LocalTime.of(8, 0)))));

    List<Map<String, Object>> written = notificationsOf(eventId);
    assertTrue(recipientsOf(eventId).contains(dispatcher.id()), "LOD-05");
    assertTrue(
        written.stream().allMatch(n -> n.get("title").equals("Released trip has no driver")));
  }

  // ---- push ---------------------------------------------------------------------------

  @Test
  void aPushIsQueuedForEachDeviceAndSentAfterCommit() throws Exception {
    UUID subscription = subscribe(manager, endpoint());

    UUID eventId = deliver("notification.on-order-deferred", deferred(outlet));
    UUID notificationId = (UUID) notificationsOf(eventId).get(0).get("notification_id");
    Map<String, Object> queued = push(notificationId);
    assertEquals("pending", queued.get("status"), "nothing leaves the process inside the consumer (R-NOT-05)");
    assertEquals(subscription, queued.get("subscription_id"));

    pushJob.runAt(Instant.now().plusSeconds(1));

    assertEquals("sent", push(notificationId).get("status"));
  }

  @Test
  void aFailingPushRetriesThenReachesDeadLetter() throws Exception {
    subscribe(manager, endpoint());
    push.answer = PushResult.RETRYABLE;
    UUID notificationId =
        (UUID) notificationsOf(deliver("notification.on-order-deferred", deferred(outlet))).get(0).get("notification_id");

    Instant at = Instant.now();
    for (int run = 1; run <= 6; run++) {
      at = at.plus(Duration.ofDays(1));
      pushJob.runAt(at);
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
        (UUID) notificationsOf(deliver("notification.on-order-deferred", deferred(outlet))).get(0).get("notification_id");

    pushJob.runAt(Instant.now().plusSeconds(1));

    assertEquals("dead", push(first).get("status"));
    assertEquals(1, ((Number) push(first).get("attempts")).intValue(), "NOT-01: not retried");
    assertEquals("expired", subscriptionStatus(subscription));
    UUID second =
        (UUID) notificationsOf(deliver("notification.on-order-deferred", deferred(outlet))).get(0).get("notification_id");
    assertTrue(
        deliveriesOf(second).stream().noneMatch(d -> d.get("channel").equals("push")),
        "an expired subscription gets nothing new");
  }

  @Test
  void withPushOffTheInboxStillWorksAndNoPushIsQueued() throws Exception {
    subscribe(manager, endpoint());
    push.enabled = false;

    UUID eventId = deliver("notification.on-order-deferred", deferred(outlet));

    UUID notificationId = (UUID) notificationsOf(eventId).get(0).get("notification_id");
    assertEquals(List.of("in_app"), deliveriesOf(notificationId).stream().map(d -> d.get("channel")).toList());
    assertEquals(
        "push is not configured on this server",
        read(manager, "/api/notifications/push-config", 200).get("reason").asText());
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
