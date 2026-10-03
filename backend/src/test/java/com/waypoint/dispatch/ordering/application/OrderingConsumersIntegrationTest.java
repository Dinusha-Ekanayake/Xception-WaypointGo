package com.waypoint.dispatch.ordering.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.issues.contract.IssueEvents.RedeliveryRequested;
import com.waypoint.dispatch.loading.contract.LoadingEvents.LoadingStarted;
import com.waypoint.dispatch.loading.contract.LoadingEvents.ReleasedStop;
import com.waypoint.dispatch.loading.contract.LoadingEvents.TripReleased;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.planning.contract.PlanEvents.OrderDeferred;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanPublished;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlanRevised;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedStop;
import com.waypoint.dispatch.planning.contract.PlanEvents.PlannedTrip;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.ConsumerInbox;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.receipt.contract.ReceiptEvents.ReceiptAutoClosed;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.support.TestDates;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.contract.WarehouseEvents.WarehouseOrderStatusChanged;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ordering's consumers and its cutoff job, against a real PostgreSQL.
 *
 * <p>No relay exists yet, so {@link #deliver} does what the relay's contract
 * says it will: open a transaction as the consumer's module for the system
 * actor, claim {@code (consumer, eventId)} in the inbox, and only then call
 * {@code on}. Delivery is at least once and in no particular order, and the
 * tests send events twice and out of sequence on purpose.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class OrderingConsumersIntegrationTest {
  static final String OUTLET = "OUT001";

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired JdbcOrderRepository orders;
  @Autowired ConsumerInbox inbox;
  @Autowired List<EventSubscriber<?>> subscribers;
  @Autowired CutoffJob cutoff;

  LocalDate serviceDate;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", () -> System.getenv("TEST_DATABASE_URL"));
  }

  @BeforeAll
  static void guardAgainstTheApplicationDatabase() {
    String application = System.getenv("DATABASE_URL");
    if (application != null && application.equals(System.getenv("TEST_DATABASE_URL"))) {
      throw new IllegalStateException("TEST_DATABASE_URL must differ from DATABASE_URL");
    }
  }

  @BeforeEach
  void setUp() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    serviceDate =
        TestDates.unusedDay(reference::nextOperatingDay);
  }

  @Test
  void anOrderFollowsItsTripFromPlanToReceiptAndLateNewsChangesNothing() {
    Order order = saved(true);
    UUID trip = UUID.randomUUID();

    UUID planned = deliver("ordering.on-plan-published", published(trip, order));
    assertEquals(OrderStatus.ALLOCATED, status(order));
    assertEquals(Optional.of(trip), current(order).tripId());

    deliver("ordering.on-loading-started", new LoadingStarted(trip, UUID.randomUUID(), "VEH001"));
    assertEquals(OrderStatus.LOADING, status(order));
    deliver("ordering.on-trip-released", released(trip, order));
    assertEquals(OrderStatus.IN_TRANSIT, status(order));
    deliver("ordering.on-delivery-completed", completed(trip, order, DeliveryOutcome.PARTIAL));
    assertEquals(OrderStatus.PARTIALLY_DELIVERED, status(order));
    deliver("ordering.on-receipt-auto-closed",
        new ReceiptAutoClosed(UUID.randomUUID(), order.orderId(), OUTLET, Instant.now()));
    assertEquals(OrderStatus.UNCONFIRMED, status(order));

    long version = current(order).rowVersion();
    redeliver("ordering.on-plan-published", planned, published(trip, order));
    deliver("ordering.on-loading-started", new LoadingStarted(trip, UUID.randomUUID(), "VEH001"));
    assertEquals(OrderStatus.UNCONFIRMED, status(order), "a late event is stale, never a regression");
    assertEquals(version, current(order).rowVersion(), "and writes nothing");

    List<Map<String, Object>> history = history(order);
    assertEquals(6, history.size(), "placement plus five moves, each recorded once");
    assertTrue(history.stream().skip(1).allMatch(h -> h.get("event_id") != null), "each move names its event");
  }

  @Test
  void aDeliveryReportedBeforeTheReleaseStillLandsAndTheReleaseIsThenStale() {
    Order order = saved(true);
    UUID trip = UUID.randomUUID();
    deliver("ordering.on-plan-published", published(trip, order));

    deliver("ordering.on-delivery-completed", completed(trip, order, DeliveryOutcome.DELIVERED));
    deliver("ordering.on-trip-released", released(trip, order));

    assertEquals(OrderStatus.DELIVERED, status(order));
  }

  @Test
  void aCancelledOrderIsNeverRevivedByAPlan() {
    Order order = saved(true);
    database.asSystem(ModuleRole.ORDERING,
        () -> orders.update(order.cancel(), 1, false, Instant.now()));

    deliver("ordering.on-plan-published", published(UUID.randomUUID(), order));

    assertEquals(OrderStatus.CANCELLED, status(order));
  }

  @Test
  void aRevisedPlanMovesTheOrderToItsNewTrip() {
    Order order = saved(true);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    deliver("ordering.on-plan-published", published(first, order));

    deliver("ordering.on-plan-revised",
        new PlanRevised(UUID.randomUUID(), order.depotCode(), serviceDate, 2, UUID.randomUUID(),
            "vehicle fault", List.of(trip(second, order))));

    assertEquals(Optional.of(second), current(order).tripId());
    assertEquals(OrderStatus.ALLOCATED, status(order));
  }

  @Test
  void aDeferralKeepsTheReservationAndMovesToTheNextOperatingDay() {
    Order order = saved(true);
    OrderDeferred deferred =
        new OrderDeferred(order.orderId(), UUID.randomUUID(), OUTLET, serviceDate, "R-PLN-07", "no vehicle", 1);

    deliver("ordering.on-order-deferred", deferred);
    Order after = current(order);
    deliver("ordering.on-order-deferred", deferred);

    assertEquals(OrderStatus.DEFERRED, after.status());
    assertEquals(reference.nextOperatingDay(serviceDate.plusDays(1)), after.deliveryDate());
    assertTrue(after.reservation().isPresent(), "D-H: deferral never releases stock");
    assertEquals(1, after.deferralCount());
    assertEquals(after.rowVersion(), current(order).rowVersion(), "the same deferral twice is one");
  }

  @Test
  void aRedeliveryIsOneLinkedConfirmedOrderPerIssue() {
    Order original = saved(true);
    UUID issue = UUID.randomUUID();
    RedeliveryRequested request =
        new RedeliveryRequested(issue, original.orderId(), serviceDate.plusDays(3), "damaged in transit");

    deliver("ordering.on-redelivery-requested", request);
    deliver("ordering.on-redelivery-requested", request);

    Order redelivery = database.asSystem(ModuleRole.ORDERING, () -> orders.findBySourceIssue(issue)).orElseThrow();
    assertEquals(OrderStatus.CONFIRMED, redelivery.status());
    assertEquals(Optional.of(original.orderId()), redelivery.redeliveryOf());
    assertEquals(original.reservation(), redelivery.reservation());
    assertEquals(1L, placedEvents(redelivery), "one issue, one redelivery");
  }

  @Test
  void theWarehouseAnsweringLateConfirmsOrCancelsAStockUnknownOrder() {
    Order reservedLate = saved(false);
    Order shortLate = saved(false);

    deliver("ordering.on-warehouse-order-status-changed",
        new WarehouseOrderStatusChanged(reservedLate.orderId(), Optional.of("WH-LATE"), "pending",
            Optional.of(new Reserved("WH-LATE", new BigDecimal("80.000"), new BigDecimal("0.5000"), "chilled", 8))));
    deliver("ordering.on-warehouse-order-status-changed",
        new WarehouseOrderStatusChanged(shortLate.orderId(), Optional.empty(), "insufficient", Optional.empty()));

    Order confirmed = current(reservedLate);
    assertEquals(OrderStatus.CONFIRMED, confirmed.status());
    assertEquals("chilled", confirmed.reservation().orElseThrow().temperature());
    assertEquals(OrderStatus.CANCELLED, status(shortLate));
  }

  @Test
  void theCutoffDefersWhatTheWarehouseNeverAnsweredAndLeavesTheRest() {
    serviceDate = reference.nextOperatingDay(
        LocalDate.of(2028, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 700)));
    Order unknown = saved(false);
    Order reserved = saved(true);
    Instant justAfter =
        serviceDate.minusDays(1).atTime(LocalTime.of(16, 0, 1)).atZone(Clock.OPERATING_ZONE).toInstant();
    Instant justBefore =
        serviceDate.minusDays(1).atTime(LocalTime.of(15, 59, 59)).atZone(Clock.OPERATING_ZONE).toInstant();

    cutoff.runAt(justBefore);
    assertEquals(OrderStatus.STOCK_UNKNOWN, status(unknown), "not before 16:00 (R-ORD-07)");

    cutoff.runAt(justAfter);
    Order deferred = current(unknown);
    assertEquals(OrderStatus.DEFERRED, deferred.status(), "R-STK-06");
    assertEquals(reference.nextOperatingDay(serviceDate.plusDays(1)), deferred.deliveryDate());
    assertTrue(deferred.reservation().isEmpty(), "R-STK-05: still not demand");
    assertEquals(OrderStatus.CONFIRMED, status(reserved));
    assertEquals("stock_unresolved", history(unknown).get(1).get("reason"));
    assertEquals(1L, eventsOf(unknown, "order.auto_deferred"));

    long version = deferred.rowVersion();
    cutoff.runAt(justAfter);
    assertEquals(version, current(unknown).rowVersion(), "a rerun of the same cutoff changes nothing");
  }

  // ---- the relay, as its contract describes it ------------------------------

  private UUID deliver(String consumer, DomainEvent payload) {
    UUID eventId = UUID.randomUUID();
    redeliver(consumer, eventId, payload);
    return eventId;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private void redeliver(String consumer, UUID eventId, DomainEvent payload) {
    EventSubscriber subscriber =
        subscribers.stream().filter(s -> s.consumerName().equals(consumer)).findFirst().orElseThrow();
    assertEquals(subscriber.eventType(), payload.getClass());
    EventEnvelope envelope =
        new EventEnvelope(eventId, payload.type(), 1, Instant.now(), "test",
            Optional.empty(), Optional.empty(), payload);
    database.asSystem(subscriber.moduleRole(), () -> {
      if (inbox.claim(consumer, eventId)) {
        subscriber.on(envelope);
      }
    });
  }

  // ---- fixtures ------------------------------------------------------------

  private Order saved(boolean reserved) {
    var outlet = reference.outlet(OUTLET, null).orElseThrow();
    String ref = "WPO-C" + UUID.randomUUID().toString().replace("-", "").substring(0, 11).toUpperCase();
    Order order =
        Order.place(
            UUID.randomUUID(), ref, OUTLET, outlet.depotCode(), outlet.brandCode(), outlet.districtName(),
            new DeliveryDate(serviceDate, serviceDate, List.of()),
            reserved
                ? Optional.of(new Reservation("WH-" + ref, new BigDecimal("120.500"), new BigDecimal("0.8000"), "ambient", 12))
                : Optional.empty(),
            List.of(new OrderLine("P-1", 12)));
    database.asSystem(ModuleRole.ORDERING, () -> {
      orders.insert(order, Actor.SYSTEM_ID, Instant.now(), UUID.randomUUID(), Optional.empty());
      orders.recordStatus(order.orderId(), Optional.empty(), order.status(), "placed", null, Optional.empty(), Instant.now());
    });
    return order;
  }

  private PlannedTrip trip(UUID tripId, Order order) {
    return new PlannedTrip(tripId, "VEH001", 1, order.brandCode(), order.districtName(), "ambient",
        LocalTime.of(5, 0), List.of(new PlannedStop(1, order.orderId(), OUTLET, LocalTime.of(8, 0))));
  }

  private PlanPublished published(UUID tripId, Order order) {
    return new PlanPublished(UUID.randomUUID(), order.depotCode(), serviceDate, 1, Optional.empty(),
        List.of(trip(tripId, order)));
  }

  private TripReleased released(UUID tripId, Order order) {
    return new TripReleased(tripId, UUID.randomUUID(), 1, "VEH001", order.depotCode(), serviceDate,
        List.of(new ReleasedStop(1, order.orderId(), OUTLET, LocalTime.of(8, 0))));
  }

  private DeliveryCompleted completed(UUID tripId, Order order, DeliveryOutcome outcome) {
    return new DeliveryCompleted(UUID.randomUUID(), order.orderId(), tripId, OUTLET, outcome,
        Optional.of(10), Instant.now(), Optional.empty());
  }

  private Order current(Order order) {
    return database.asSystem(ModuleRole.ORDERING, () -> orders.find(order.orderId())).orElseThrow();
  }

  private OrderStatus status(Order order) {
    return current(order).status();
  }

  private List<Map<String, Object>> history(Order order) {
    return database.asSystem(ModuleRole.ORDERING, () -> database.query(
        "SELECT reason, event_id FROM ordering.order_status_history WHERE order_id = ? ORDER BY history_id",
        order.orderId()));
  }

  private long placedEvents(Order order) {
    return eventsOf(order, "order.placed");
  }

  private long eventsOf(Order order, String type) {
    return ((Number) database.asModule(ModuleRole.INTEGRATION, null, () -> database.queryOne(
        "SELECT count(*) AS n FROM integration.outbox_events WHERE aggregate_id = ? AND event_type = ?",
        order.orderId().toString(), type)).get("n")).longValue();
  }
}
