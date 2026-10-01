package com.waypoint.dispatch.warehouse.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.support.TestDatabase;
import com.waypoint.dispatch.warehouse.application.InboundEvents.Landing;
import com.waypoint.dispatch.warehouse.contract.StockPort;
import com.waypoint.dispatch.warehouse.contract.StockPort.ConfirmResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Confirmed;
import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementRequest;
import com.waypoint.dispatch.warehouse.contract.StockPort.PlacementResult;
import com.waypoint.dispatch.warehouse.contract.StockPort.Reserved;
import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import com.waypoint.dispatch.warehouse.contract.StockPort.Unavailable;
import com.waypoint.dispatch.warehouse.contract.CatalogueQuery;
import com.waypoint.dispatch.warehouse.domain.Placement;
import com.waypoint.dispatch.warehouse.domain.Placement.State;
import com.waypoint.dispatch.warehouse.domain.WebhookSignature;
import com.waypoint.dispatch.warehouse.infrastructure.WarehouseHttpClient;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcPlacementRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcStatusRequestRepository;
import com.waypoint.dispatch.warehouse.infrastructure.JdbcStatusRequestRepository.StatusRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The warehouse adapter against a stub that behaves like the real API: the
 * lifecycle, the {@code 201}/{@code 202}/{@code 409} placement, and no
 * idempotency key. Time is real but the timeouts are short, and the retry and
 * status jobs are called directly so nothing runs on a timetable.
 *
 * <p>Every test uses its own products and order references, because the
 * database outlives the test run.
 */
@SpringBootTest
@ExtendWith(TestDatabase.class)
class WarehouseIntegrationTest {
  private static final String SECRET = "test-webhook-secret";
  private static final StubWarehouse STUB;

  static {
    try {
      STUB = new StubWarehouse();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
    registry.add("app.warehouse.base-url", STUB::baseUrl);
    registry.add("app.warehouse.api-key", () -> StubWarehouse.KEY);
    registry.add("app.warehouse.timeout-ms", () -> "800");
    registry.add("app.warehouse.circuit-failures", () -> "3");
    registry.add("app.warehouse.circuit-open-for", () -> "1s");
    registry.add("app.warehouse.webhook-secret", () -> SECRET);
    registry.add("app.scheduling.enabled", () -> "false");
  }

  @AfterAll
  static void stopStub() {
    STUB.close();
  }

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired StockPort port;
  @Autowired StockUnknownRetryJob retry;
  @Autowired StatusRequestJob statusJob;
  @Autowired WarehouseReconciler reconciler;
  @Autowired InboundEvents inbound;
  @Autowired CatalogueSyncJob catalogueSync;
  @Autowired CatalogueQuery catalogue;
  @Autowired WarehouseHttpClient client;
  @Autowired JdbcPlacementRepository placements;
  @Autowired JdbcStatusRequestRepository statusRequests;

  private String product;

  @BeforeEach
  void setUp() {
    migrator.migrate();
    STUB.reset();
    product = "P-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // ---- placement -------------------------------------------------------------

  @Test
  void aCreatedOrderIsReservedWithTheWarehousesTotalsAndReplayedNotPlacedTwice() {
    PlacementRequest request = request(4);

    Reserved first = assertInstanceOf(Reserved.class, port.placeOrder(request));
    PlacementResult again = port.placeOrder(request);

    assertEquals(10.0, first.weightKg().doubleValue(), 0.001, "totals are the warehouse's (D-E)");
    assertEquals("ambient", first.temperature());
    assertEquals(first, again, "the same request answers from the record");
    assertEquals(1, STUB.posts.get(), "R-STK-11: one POST however often it is asked");
  }

  @Test
  void nothingAvailableIsInsufficientWithPerLineQuantities() {
    STUB.stock(product, 0);

    Insufficient result = assertInstanceOf(Insufficient.class, port.placeOrder(request(4)));

    assertEquals(4, result.lines().get(0).requested());
    assertEquals(0, result.lines().get(0).available());
    assertEquals(State.REJECTED, placementOf(lastRef).state());
  }

  @Test
  void aShortOrderIsKeptPartiallyReservedAndConfirmedWithTheLockedQuantities() {
    STUB.stock(product, 3);

    PartiallyReserved partial = assertInstanceOf(PartiallyReserved.class, port.placeOrder(request(5)));

    assertEquals(3, partial.lines().get(0).available());
    assertEquals(5, partial.lines().get(0).requested());
    assertEquals("PLG", partial.alternatives().get(0).warehouse());
    String ref = partial.reservation().warehouseOrderRef();
    assertEquals("reserved", STUB.order(ref).status, "the lock is held, not cancelled");

    ConfirmResult confirmed = port.confirmReservation(ref);

    Confirmed ok = assertInstanceOf(Confirmed.class, confirmed);
    assertEquals(3, ok.lines().get(0).quantity());
    assertEquals("pending", STUB.order(ref).status);
    assertInstanceOf(Confirmed.class, port.confirmReservation(ref),
        "a second confirm finds it already pending: the same answer, not an error");
    STUB.forceStatus(ref, "cancelled");
    assertInstanceOf(StockPort.Expired.class, port.confirmReservation(ref), "cancelled holds nothing");
  }

  // ---- unknown outcomes ------------------------------------------------------

  @Test
  void aTimeoutThenRetryAdoptsTheExistingOrderWithoutASecondPost() {
    STUB.slowPostMs = 1800;
    PlacementRequest request = request(4);

    PlacementResult result = port.placeOrder(request);

    assertInstanceOf(Unavailable.class, result, "STK-04: no answer inside the budget");
    sleep(2000);
    assertEquals(1, STUB.all().size(), "the warehouse did create it");
    Placement unknown = placementOf(lastRef);
    assertEquals(State.UNKNOWN, unknown.state());

    STUB.slowPostMs = 0;
    retry.settle(unknown, Instant.now());

    String diag = "attempts=" + unknown.attempts() + " orders="
        + STUB.all().stream().map(o -> o.id + "@" + o.createdAt + "/" + o.status + "/" + o.warehouse + o.items).toList()
        + " list=" + client.listOrders("KDY", "pending", 1, 50) + " circuit=" + client.circuitState();
    assertEquals(1, STUB.posts.get(), "STK-09: found by content, never posted again");
    assertEquals(1, STUB.all().size());
    Placement settled = placementOf(lastRef);
    assertEquals(State.PLACED, settled.state(), diag);
    assertEquals(STUB.all().get(0).id, settled.warehouseOrderRef().orElseThrow());
    List<Map<String, Object>> events = outbox(request.orderId());
    assertEquals(1, events.size());
    assertEquals("warehouse.order_status_changed", events.get(0).get("event_type"));
    assertTrue(String.valueOf(events.get(0).get("payload")).contains("pending"), events.toString());
  }

  @Test
  void aLateAnswerIsAdoptedByContentNotPlacedAgain() {
    STUB.slowPostMs = 1800;
    PlacementRequest request = request(2);
    port.placeOrder(request);
    sleep(2000);
    STUB.slowPostMs = 0;

    retry.settle(placementOf(lastRef), Instant.now());
    retry.settle(placementOf(lastRef), Instant.now());

    assertEquals(1, STUB.posts.get(), "STK-05");
    assertEquals(1, outbox(request.orderId()).size(), "announced once");
  }

  @Test
  void theCircuitOpensAndAnswersUnavailableAtOnceThenClosesAfterATrial() {
    STUB.failWith = 503;
    for (int i = 0; i < 3; i++) {
      assertInstanceOf(Unavailable.class, port.placeOrder(request(1)));
    }
    int callsWhenOpen = STUB.calls.get();

    long started = System.nanoTime();
    Unavailable open = assertInstanceOf(Unavailable.class, port.placeOrder(request(1)));

    assertEquals("warehouse circuit open", open.reason());
    assertEquals(callsWhenOpen, STUB.calls.get(), "STK-04: no call is made while open");
    assertTrue((System.nanoTime() - started) / 1_000_000 < 250, "answered at once");
    assertEquals(State.QUEUED, placementOf(lastRef).state(), "saved to send later, never lost");

    STUB.failWith = 0;
    sleep(1100);
    assertInstanceOf(Reserved.class, port.placeOrder(request(1)), "the trial succeeds and closes it");
    assertInstanceOf(Reserved.class, port.placeOrder(request(1)));
  }

  // ---- status calls and reconciliation ---------------------------------------

  @Test
  void anInvalidTransitionIsRaisedOnceNeverLooped() {
    Reserved reserved = assertInstanceOf(Reserved.class, port.placeOrder(request(2)));
    UUID orderId = placementOf(lastRef).orderId();
    STUB.forceStatus(reserved.warehouseOrderRef(), "delivered");
    queue(orderId, reserved.warehouseOrderRef(), "cancelled");

    statusJob.apply(dueRequest(reserved.warehouseOrderRef()), Instant.now());

    assertTrue(due(reserved.warehouseOrderRef()).isEmpty(), "STK-10: not retried; circuit=" + client.circuitState()
        + " now=" + client.order(reserved.warehouseOrderRef()) + " due=" + due(reserved.warehouseOrderRef()));
    assertEquals(1L, discrepancyCount(reserved.warehouseOrderRef(), "invalid_transition"));
    assertEquals("warehouse.discrepancy_found", outbox(orderId).get(0).get("event_type"));
  }

  @Test
  void aStatusAlreadyReachedIsDoneNotADivergence() {
    Reserved reserved = assertInstanceOf(Reserved.class, port.placeOrder(request(2)));
    UUID orderId = placementOf(lastRef).orderId();
    STUB.forceStatus(reserved.warehouseOrderRef(), "cancelled");
    queue(orderId, reserved.warehouseOrderRef(), "cancelled");

    statusJob.apply(dueRequest(reserved.warehouseOrderRef()), Instant.now());

    assertTrue(due(reserved.warehouseOrderRef()).isEmpty());
    assertEquals(0L, discrepancyCount(reserved.warehouseOrderRef(), "invalid_transition"));
  }

  @Test
  void aCancellationOutsideWaypointIsRaised() {
    Reserved reserved = assertInstanceOf(Reserved.class, port.placeOrder(request(2)));
    UUID orderId = placementOf(lastRef).orderId();
    STUB.forceStatus(reserved.warehouseOrderRef(), "cancelled");

    reconciler.poll(Instant.now(), 500);

    assertEquals(1L, discrepancyCount(reserved.warehouseOrderRef(), "cancelled_outside"), "STK-11");
    assertTrue(outbox(orderId).stream().anyMatch(e ->
        "warehouse.order_status_changed".equals(e.get("event_type"))
            && String.valueOf(e.get("payload")).contains("cancelled")));
    assertEquals(State.RELEASED, placementOf(lastRef).state());
  }

  @Test
  void anOrderWithNoWaypointOrderIsReleasedAndRaised() {
    Reserved reserved = assertInstanceOf(Reserved.class, port.placeOrder(request(2)));

    reconciler.reconcile(Instant.now().plusSeconds(3600), Optional.empty(), 500);

    assertEquals(1L, discrepancyCount(reserved.warehouseOrderRef(), "orphan_released"), "STK-08");
    assertTrue(due(reserved.warehouseOrderRef()).stream().anyMatch(r -> "cancelled".equals(r.targetStatus())),
        "the orphan is cancelled so its stock comes back");
  }

  // ---- webhook ---------------------------------------------------------------

  @Test
  void aBadSignatureIsStoredQuarantinedAndNeverProcessed() {
    String body = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"order.status_changed\",\"data\":{}}";

    Landing landing = inbound.land(bytes(body), ts(), "sha256=" + "00".repeat(32));

    assertEquals(Landing.UNVERIFIED, landing, "SEC-18");
    Map<String, Object> row = inboundRow(idOf(body));
    assertEquals("quarantined", row.get("status"));
    assertFalse((Boolean) row.get("signature_verified"));
    inbound.run(Instant.now());
    assertEquals("quarantined", inboundRow(idOf(body)).get("status"), "the job never touches it");
    assertThrows(Exception.class, () -> database.asSystem(ModuleRole.WAREHOUSE, () ->
        database.update("UPDATE warehouse.inbound_events SET status = 'processed' WHERE source_event_id = ?",
            idOf(body))), "the CHECK makes processing an unverified row impossible");
  }

  @Test
  void aMissingOrStaleSignatureIsNotAccepted() {
    String body = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"order.status_changed\",\"data\":{}}";
    assertEquals(Landing.UNVERIFIED, inbound.land(bytes(body), null, null));
    String old = String.valueOf(Instant.now().minusSeconds(3600).getEpochSecond());
    String stale = "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"order.status_changed\",\"data\":{}}";
    assertEquals(Landing.UNVERIFIED, inbound.land(bytes(stale), old, sign(old, stale)), "SEC-19: replay window");
  }

  @Test
  void aSignedEventIsAppliedOnceAndADuplicateIsANoOp() {
    Reserved reserved = assertInstanceOf(Reserved.class, port.placeOrder(request(2)));
    UUID orderId = placementOf(lastRef).orderId();
    STUB.forceStatus(reserved.warehouseOrderRef(), "cancelled");
    String body = event("order.status_changed", reserved.warehouseOrderRef(), "cancelled");
    String ts = ts();

    assertEquals(Landing.ACCEPTED, inbound.land(bytes(body), ts, sign(ts, body)));
    assertEquals(Landing.DUPLICATE, inbound.land(bytes(body), ts, sign(ts, body)), "SEC-19");
    inbound.run(Instant.now());

    assertEquals("processed", inboundRow(idOf(body)).get("status"));
    assertEquals(State.RELEASED, placementOf(lastRef).state());
    assertEquals(1L, outbox(orderId).stream().filter(e ->
        "warehouse.order_status_changed".equals(e.get("event_type"))).count());
  }

  @Test
  void aWebhookForAnUnknownOrderIsQuarantined() {
    String body = event("order.status_changed", "ORD-NOBODY-" + product, "cancelled");
    String ts = ts();

    assertEquals(Landing.ACCEPTED, inbound.land(bytes(body), ts, sign(ts, body)));
    inbound.run(Instant.now());

    assertEquals("quarantined", inboundRow(idOf(body)).get("status"), "STK-06: never auto-applied");
  }

  @Test
  void anUnknownEventTypeIsQuarantinedNotIgnored() {
    String body = event("something.new", "x", "y");
    String ts = ts();

    inbound.land(bytes(body), ts, sign(ts, body));

    assertEquals("quarantined", inboundRow(idOf(body)).get("status"), "SEC-14");
  }

  // ---- catalogue -------------------------------------------------------------

  @Test
  void theCatalogueSyncsOnceAndAFailedSyncKeepsTheLastCopyMarkedStale() {
    STUB.listProduct(product + "-A");
    STUB.listProduct(product + "-B");

    assertEquals("changed", catalogueSync.sync(Instant.now()));
    assertEquals("unchanged", catalogueSync.sync(Instant.now()), "same content, same version, no event");
    assertEquals(Optional.of("chilled"), catalogue.product(product + "-A").orElseThrow().temperature());
    assertFalse(catalogue.product(product + "-A").orElseThrow().verifiedRealSku(), "CAT-06: shown as inferred");

    STUB.catalogueDown = true;
    assertEquals("failed", catalogueSync.sync(Instant.now()));

    assertTrue(catalogue.product(product + "-A").isPresent(), "CAT-01: the last good copy stays");
    assertFalse(catalogue.status().stale(), "just synced, so not yet stale");
    assertTrue(catalogue.status().ageSeconds() >= 0);
  }

  // ---- helpers ---------------------------------------------------------------

  private String lastRef;

  private PlacementRequest request(int quantity) {
    lastRef = "WPO-" + UUID.randomUUID().toString().substring(0, 12);
    return new PlacementRequest(UUID.randomUUID(), lastRef, "Kandy", List.of(new StockLine(product, quantity)));
  }

  private Placement placementOf(String ref) {
    return database.asSystem(ModuleRole.WAREHOUSE, () -> placements.findByRef(ref)).orElseThrow();
  }

  private void queue(UUID orderId, String warehouseRef, String target) {
    database.asSystem(ModuleRole.WAREHOUSE, () ->
        statusRequests.request(UUID.randomUUID(), orderId, warehouseRef, target, "test", Instant.now()));
  }

  private List<StatusRequest> due(String warehouseRef) {
    return database.asSystem(ModuleRole.WAREHOUSE, () -> statusRequests.due(Instant.now().plusSeconds(60), 500))
        .stream().filter(r -> r.warehouseOrderRef().equals(warehouseRef)).toList();
  }

  private StatusRequest dueRequest(String warehouseRef) {
    return due(warehouseRef).get(0);
  }

  private long discrepancyCount(String warehouseRef, String kind) {
    return ((Number) database.asSystem(ModuleRole.WAREHOUSE, () -> database.queryOne(
        "SELECT count(*) AS n FROM warehouse.discrepancies WHERE warehouse_order_ref = ? AND kind = ?",
        warehouseRef, kind)).get("n")).longValue();
  }

  private List<Map<String, Object>> outbox(UUID orderId) {
    return database.asModule(ModuleRole.INTEGRATION, null, () -> database.query(
        "SELECT * FROM integration.outbox_events WHERE aggregate_id = ? ORDER BY occurred_at, event_id",
        orderId.toString()));
  }

  private Map<String, Object> inboundRow(String sourceEventId) {
    return database.asSystem(ModuleRole.WAREHOUSE, () -> database.queryOne(
        "SELECT * FROM warehouse.inbound_events WHERE source_system = 'warehouse' AND source_event_id = ?",
        sourceEventId));
  }

  private static String event(String type, String orderId, String status) {
    return "{\"id\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type
        + "\",\"data\":{\"order_id\":\"" + orderId + "\",\"status\":\"" + status + "\"}}";
  }

  private static String idOf(String body) {
    int i = body.indexOf("\"id\":\"") + 6;
    return body.substring(i, body.indexOf('"', i));
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static String ts() {
    return String.valueOf(Instant.now().getEpochSecond());
  }

  private static String sign(String ts, String body) {
    return "sha256=" + HexFormat.of().formatHex(
        WebhookSignature.sign(SECRET.getBytes(StandardCharsets.UTF_8), ts, bytes(body)));
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
