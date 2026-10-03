package com.waypoint.dispatch.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.ordering.OrderingTestConfig.MovableClock;
import com.waypoint.dispatch.ordering.OrderingTestConfig.ScriptedStockPort;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.Command;
import com.waypoint.dispatch.platform.messaging.CommandBus;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.support.TestDates;
import com.waypoint.dispatch.warehouse.contract.StockPort.Insufficient;
import com.waypoint.dispatch.warehouse.contract.StockPort.LineAvailability;
import com.waypoint.dispatch.warehouse.contract.StockPort.Unavailable;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Ordering's commands end to end through {@code POST /api/commands}: the bus
 * authorizes, the handler decides, and the order, its history, its event, the
 * receipt and the audit row commit together.
 *
 * <p>The warehouse is scripted and the clock is moved, so the cutoff and each
 * stock answer are exercised on purpose rather than by the time of day.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderingTestConfig.class)
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class OrderingCommandIntegrationTest {
  static final String OUTLET = "OUT001";
  static final String PASSWORD = "OrderingCommands2026!";

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired CommandBus bus;
  @Autowired ScriptedStockPort stock;
  @Autowired MovableClock clock;

  String depot;
  String managerEmail;
  String strangerEmail;
  String dispatcherEmail;
  Cookie manager;
  Cookie stranger;
  Cookie dispatcher;
  UUID managerId;
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
    clock.reset();
    stock.reset();
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    depot = reference.outlet(OUTLET, null).orElseThrow().depotCode();

    String run = UUID.randomUUID().toString().substring(0, 8);
    managerEmail = "ocm-" + run + "@ordering.test";
    strangerEmail = "ocs-" + run + "@ordering.test";
    dispatcherEmail = "ocd-" + run + "@ordering.test";
    managerId = accounts.createAccount(managerEmail, "Manager", PASSWORD, "store_manager");
    accounts.createAccount(strangerEmail, "Stranger", PASSWORD, "store_manager");
    accounts.createAccount(dispatcherEmail, "Dispatcher", PASSWORD, "dispatcher");
    accounts.grantDepot(dispatcherEmail, depot);
    database.asModule(
        ModuleRole.IAM,
        null,
        () ->
            database.update(
                "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)",
                managerId,
                OUTLET));
    // A day no earlier run has closed, with "now" the morning before it.
    serviceDate =
        TestDates.unusedDay(reference::nextOperatingDay);
    moveTo(at(serviceDate.minusDays(1), LocalTime.of(10, 0)));
  }

  @AfterEach
  void tearDown() {
    clock.reset();
    stock.reset();
  }

  @Test
  void aReservedPlacementIsConfirmedOnceHoweverOftenItIsSent() throws Exception {
    String body = place(UUID.randomUUID(), serviceDate);

    JsonNode first = mapper.readTree(send(manager, body, 200));
    JsonNode result = first.get("result");
    assertFalse(first.get("replayed").asBoolean());
    assertEquals("CONFIRMED", result.get("status").asText());
    assertEquals(serviceDate.toString(), result.get("deliveryDate").asText());
    assertFalse(result.get("dateRolled").asBoolean());
    // The "order sent" screen reads these from the answer, so they are the warehouse's.
    assertEquals(12, result.get("itemCount").asInt());
    assertTrue(result.hasNonNull("weightKg") && result.hasNonNull("volumeM3"), result.toString());
    assertTrue(result.hasNonNull("temperature"), result.toString());
    assertEquals(1, result.get("lines").size());

    JsonNode second = mapper.readTree(send(manager, body, 200));
    assertTrue(second.get("replayed").asBoolean(), "ORD-04: a double tap returns the original");
    assertEquals(result, second.get("result"));
    assertEquals(1, stock.calls.get(), "the warehouse is not asked twice");

    UUID orderId = UUID.fromString(result.get("orderId").asText());
    List<Map<String, Object>> events = outbox(orderId);
    assertEquals(1, events.size());
    assertEquals("order.placed", events.get(0).get("event_type"));

    JsonNode view = mapper.readTree(read(manager, "/api/orders/" + orderId, 200));
    assertEquals(
        0,
        new java.math.BigDecimal("30.000").compareTo(view.get("weightKg").decimalValue()),
        "totals are the warehouse's (R-ORD-12)");
    read(stranger, "/api/orders/" + orderId, 404);
  }

  @Test
  void anUnreachableWarehouseSavesTheOrderAsStockUnknownAndSaysSo() throws Exception {
    stock.answer(lines -> new Unavailable("circuit open"));

    JsonNode result = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");

    assertEquals("STOCK_UNKNOWN", result.get("status").asText());
    assertEquals("circuit open", result.get("degraded").asText(), "degrade visibly (rule 9)");
    assertTrue(result.get("weightKg").isNull(), "no totals are guessed while stock is unchecked");
    assertTrue(result.get("itemCount").isNull(), result.toString());
    assertEquals(1, result.get("lines").size(), "the lines asked for are still shown");
  }

  @Test
  void aReplyWithoutTotalsIsStockUnknownNeverGuessed() throws Exception {
    stock.answer(lines -> new com.waypoint.dispatch.warehouse.contract.StockPort.Reserved("WH-BARE", null, null, null, 0));

    JsonNode result = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");

    assertEquals("STOCK_UNKNOWN", result.get("status").asText(), "STK-07");
    assertTrue(result.get("degraded").asText().contains("WH-BARE"), result.toString());
  }

  @Test
  void aConfirmedOrderIsNotAmendedWhileTheWarehouseIsDown() throws Exception {
    UUID orderId = placed();
    stock.answer(lines -> new Unavailable("circuit open"));

    String problem = send(manager, amend(UUID.randomUUID(), orderId, 1L, 20), 503);

    assertTrue(problem.contains("unchanged"), problem);
    JsonNode view = mapper.readTree(read(manager, "/api/orders/" + orderId, 200));
    assertEquals(1, view.get("rowVersion").asInt(), "ORD-14: nothing was written");
    assertEquals(12, view.get("itemCount").asInt());
  }

  @Test
  void aShortLineRejectsThePlacementWithTheAvailableQuantities() throws Exception {
    stock.answer(
        lines -> new Insufficient(List.of(new LineAvailability("P-1", 12, 4))));
    UUID commandId = UUID.randomUUID();

    String problem = send(manager, place(commandId, serviceDate), 422);

    assertTrue(problem.contains("P-1 requested 12, available 4"), problem);
    assertTrue(problem.contains("STK-01"), problem);
    JsonNode availability = mapper.readTree(problem).get("availability");
    assertEquals("P-1", availability.get(0).get("productId").asText(), problem);
    assertEquals(4, availability.get(0).get("available").asInt());
    // A refusal for a rule it broke is the command's answer (R-PLT-06): the retry of
    // this command id gets the same refusal. Nothing was placed.
    assertEquals(1L, count(ModuleRole.INTEGRATION,
        "SELECT count(*) AS n FROM integration.command_receipts"
            + " WHERE command_id = ? AND result_status >= 400", commandId));
    String replayed = send(manager, place(commandId, serviceDate), 422);
    assertEquals(
        availability, mapper.readTree(replayed).get("availability"), "the retry is told the same quantities");
  }

  @Test
  void placingForAnotherOutletIsForbiddenAndAudited() throws Exception {
    UUID strangerId = userId(strangerEmail);
    long before = denials(strangerId, "order:Place");

    send(stranger, place(UUID.randomUUID(), serviceDate), 403);

    assertEquals(before + 1, denials(strangerId, "order:Place"), "ORD-09: 403 plus audit");
  }

  @Test
  void aPlacementAfterTheCutoffRollsToTheNextRun() throws Exception {
    moveTo(at(serviceDate.minusDays(1), LocalTime.of(16, 0)));

    JsonNode result = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");

    assertTrue(result.get("dateRolled").asBoolean(), "ORD-01");
    assertTrue(result.get("rolledBecause").toString().contains("cutoff"));
    assertTrue(LocalDate.parse(result.get("deliveryDate").asText()).isAfter(serviceDate));
  }

  @Test
  void anAmendNeedsTheCurrentVersionAndReReserves() throws Exception {
    UUID orderId = placed();

    String stale = send(manager, amend(UUID.randomUUID(), orderId, 7L, 2), 409);
    assertTrue(stale.contains("VERSION_CONFLICT"), stale);

    JsonNode amended = mapper.readTree(send(manager, amend(UUID.randomUUID(), orderId, 1L, 20), 200));
    assertEquals(2, amended.get("result").get("rowVersion").asInt());
    JsonNode view = mapper.readTree(read(manager, "/api/orders/" + orderId, 200));
    assertEquals(20, view.get("itemCount").asInt());
    assertEquals("P-1", view.get("lines").get(0).get("productId").asText());
    assertEquals(20, view.get("lines").get(0).get("quantity").asInt());
  }

  @Test
  void twoParallelAmendsOfOneVersionLetExactlyOneWin() throws Exception {
    UUID orderId = placed();
    stock.delay(300);
    Actor actor = Actor.user(managerId);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Future<String>> outcomes = new ArrayList<>();
    for (int quantity : new int[] {5, 9}) {
      Callable<String> amendIt =
          () -> {
            try {
              bus.dispatch(actor, command(UUID.randomUUID(), "order:Amend", 1L,
                  "{\"orderId\":\"" + orderId + "\",\"lines\":[{\"productId\":\"P-1\",\"quantity\":" + quantity + "}]}"));
              return "ok";
            } catch (DomainException e) {
              return e.code().name();
            }
          };
      outcomes.add(pool.submit(amendIt));
    }
    List<String> results = new ArrayList<>();
    for (Future<String> f : outcomes) {
      results.add(f.get());
    }
    pool.shutdown();

    assertEquals(1, results.stream().filter("ok"::equals).count(), results.toString());
    assertEquals(
        1, results.stream().filter(ErrorCode.VERSION_CONFLICT.name()::equals).count(), results.toString());
  }

  @Test
  void aCancellationReleasesTheReservationThroughItsEvent() throws Exception {
    UUID orderId = placed();

    JsonNode result =
        mapper.readTree(
            send(manager, envelope(UUID.randomUUID(), "order:Cancel", 1L,
                "{\"orderId\":\"" + orderId + "\",\"reason\":\"Ordered twice\"}"), 200));

    assertEquals("CANCELLED", result.get("result").get("status").asText());
    Map<String, Object> event =
        outbox(orderId).stream().filter(e -> "order.cancelled".equals(e.get("event_type"))).findFirst().orElseThrow();
    JsonNode payload = mapper.readTree(String.valueOf(event.get("payload")));
    assertTrue(payload.get("warehouseOrderRef").asText().startsWith("WH-"), payload.toString());

    String again = send(manager, envelope(UUID.randomUUID(), "order:Cancel", 2L,
        "{\"orderId\":\"" + orderId + "\",\"reason\":\"Ordered twice\"}"), 409);
    assertTrue(again.contains("ORD-10"), again);
  }

  private static final String PARTIAL_REF = "WH-PART";

  private void partialAnswer() {
    stock.answer(
        lines ->
            new com.waypoint.dispatch.warehouse.contract.StockPort.PartiallyReserved(
                new com.waypoint.dispatch.warehouse.contract.StockPort.Reserved(
                    PARTIAL_REF, new java.math.BigDecimal("7.500"),
                    new java.math.BigDecimal("0.0300"), "ambient", 3),
                java.time.Instant.parse("2040-01-01T00:00:00Z"),
                List.of(new LineAvailability("P-1", 12, 3)),
                List.of(new com.waypoint.dispatch.warehouse.contract.StockPort.Alternative(
                    "P-1", "PLG", 1000))));
  }

  @Test
  void aPartialReservationIsKeptAndTheStoreIsToldWhatIsShort() throws Exception {
    partialAnswer();

    JsonNode result = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");

    assertEquals("PARTIALLY_RESERVED", result.get("status").asText(), "STK-13: kept, not rejected");
    JsonNode shortfall = result.get("shortfall");
    assertEquals(PARTIAL_REF, shortfall.get("warehouseOrderRef").asText());
    assertEquals(3, shortfall.get("lines").get(0).get("reserved").asInt());
    assertEquals("PLG", shortfall.get("otherWarehouse").get(0).get("warehouse").asText());
    UUID orderId = UUID.fromString(result.get("orderId").asText());
    JsonNode view = mapper.readTree(read(manager, "/api/orders/" + orderId, 200));
    assertEquals(3, view.get("itemCount").asInt(), "totals are the locked quantities'");
    assertEquals(12, view.get("lines").get(0).get("quantity").asInt(), "what the store asked for");
  }

  @Test
  void acceptingTheShortfallConfirmsTheLockedQuantities() throws Exception {
    partialAnswer();
    JsonNode placed = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");
    UUID orderId = UUID.fromString(placed.get("orderId").asText());
    stock.confirmWith(
        () ->
            new com.waypoint.dispatch.warehouse.contract.StockPort.Confirmed(
                new com.waypoint.dispatch.warehouse.contract.StockPort.Reserved(
                    PARTIAL_REF, new java.math.BigDecimal("7.500"),
                    new java.math.BigDecimal("0.0300"), "ambient", 3),
                List.of(new com.waypoint.dispatch.warehouse.contract.StockPort.StockLine("P-1", 3))));

    JsonNode accepted =
        mapper.readTree(
            send(manager, envelope(UUID.randomUUID(), "order:AcceptShortfall", 1L,
                "{\"orderId\":\"" + orderId + "\"}"), 200)).get("result");

    assertEquals("CONFIRMED", accepted.get("status").asText());
    JsonNode view = mapper.readTree(read(manager, "/api/orders/" + orderId, 200));
    assertEquals(3, view.get("lines").get(0).get("quantity").asInt(), "lines become the locked quantities");
    assertEquals(2, view.get("rowVersion").asInt());
  }

  @Test
  void acceptingAfterTheLockRanOutCancelsTheOrder() throws Exception {
    partialAnswer();
    JsonNode placed = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");
    UUID orderId = UUID.fromString(placed.get("orderId").asText());
    stock.confirmWith(
        () -> new com.waypoint.dispatch.warehouse.contract.StockPort.Expired("the reservation is expired"));

    JsonNode result =
        mapper.readTree(
            send(manager, envelope(UUID.randomUUID(), "order:AcceptShortfall", 1L,
                "{\"orderId\":\"" + orderId + "\"}"), 200)).get("result");

    assertEquals("CANCELLED", result.get("status").asText(), "STK-13");
    assertEquals("reservation_expired", result.get("reason").asText());
    assertTrue(
        outbox(orderId).stream().anyMatch(e -> "order.cancelled".equals(e.get("event_type"))),
        "the warehouse learns of it through the event");
  }

  @Test
  void aWarehouseThatCannotConfirmLeavesTheOrderUnchanged() throws Exception {
    partialAnswer();
    JsonNode placed = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");
    UUID orderId = UUID.fromString(placed.get("orderId").asText());
    stock.confirmWith(() -> new Unavailable("circuit open"));

    send(manager, envelope(UUID.randomUUID(), "order:AcceptShortfall", 1L,
        "{\"orderId\":\"" + orderId + "\"}"), 503);

    JsonNode view = mapper.readTree(read(manager, "/api/orders/" + orderId, 200));
    assertEquals("PARTIALLY_RESERVED", view.get("status").asText());
    assertEquals(1, view.get("rowVersion").asInt());
  }

  @Test
  void aDayClosesOnlyAfterTheCutoffAndThenPlacementRollsPastIt() throws Exception {
    String close = "{\"depotCode\":\"" + depot + "\",\"serviceDate\":\"" + serviceDate + "\"}";

    String early = send(dispatcher, envelope(UUID.randomUUID(), "order:CloseForDay", null, close), 409);
    assertTrue(early.contains("R-ORD-01"), early);

    moveTo(at(serviceDate.minusDays(1), LocalTime.of(16, 5)));
    JsonNode closed = mapper.readTree(send(dispatcher, envelope(UUID.randomUUID(), "order:CloseForDay", null, close), 200));
    assertFalse(closed.get("result").get("alreadyClosed").asBoolean());
    JsonNode twice = mapper.readTree(send(dispatcher, envelope(UUID.randomUUID(), "order:CloseForDay", null, close), 200));
    assertTrue(twice.get("result").get("alreadyClosed").asBoolean());

    // Back to the morning: the cutoff has not passed, but the day is closed.
    moveTo(at(serviceDate.minusDays(1), LocalTime.of(10, 0)));
    JsonNode rolled = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");
    assertTrue(rolled.get("rolledBecause").toString().contains("closed"), rolled.toString());

    send(manager, envelope(UUID.randomUUID(), "order:CloseForDay", null, close), 403);
  }

  @Test
  void aDispatcherReadsEveryOrderDueAtTheDepotThatDay() throws Exception {
    UUID first = placed();
    UUID second = placed();
    String path = "/api/orders/day?depot=" + depot + "&date=";

    JsonNode day = mapper.readTree(read(dispatcher, path + serviceDate, 200));
    java.util.Set<String> ids = new java.util.HashSet<>();
    day.forEach(order -> ids.add(order.get("orderId").asText()));
    assertTrue(ids.containsAll(List.of(first.toString(), second.toString())), day.toString());
    day.forEach(order -> assertEquals(serviceDate.toString(), order.get("deliveryDate").asText()));

    JsonNode otherDay = mapper.readTree(read(dispatcher, path + serviceDate.plusDays(400), 200));
    otherDay.forEach(order -> assertFalse(ids.contains(order.get("orderId").asText())));

    // A store manager holds order:Read but no depot: 403 plus audit, never an empty board.
    long before = denials(managerId, "order:Read");
    read(manager, path + serviceDate, 403);
    assertEquals(before + 1, denials(managerId, "order:Read"));
  }

  @Test
  void aTechStoreIsToldWhichNearbyDayATripAlreadyServesItsDistrict() throws Exception {
    // OUT023 and OUT024 are Tech outlets in Colombo; the manager runs both.
    database.asModule(
        ModuleRole.IAM,
        null,
        () -> {
          for (String outlet : List.of("OUT023", "OUT024")) {
            database.update(
                "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)",
                managerId,
                outlet);
          }
        });
    LocalDate busy = reference.nextOperatingDay(serviceDate.plusDays(1));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        !busy.isAfter(serviceDate.plusDays(2)), "the next run is within reach");
    send(manager, placeFor(UUID.randomUUID(), "OUT024", busy), 200);
    String path = "/api/orders/ride-along?outlet=OUT023&requestedDate=" + serviceDate;

    JsonNode hint = mapper.readTree(read(manager, path, 200));

    assertTrue(hint.get("offered").asBoolean(), hint.toString());
    assertEquals(serviceDate.toString(), hint.get("deliveryDate").asText());
    assertEquals(1, hint.get("days").size(), hint.toString());
    assertEquals(busy.toString(), hint.get("days").get(0).get("date").asText());
    assertEquals(1, hint.get("days").get(0).get("stopsBooked").asInt());
    assertFalse(hint.toString().contains("OUT024"), "a count, never which outlet");

    // R-ORD-13: Fresh is never moved off its day.
    JsonNode fresh =
        mapper.readTree(
            read(manager, "/api/orders/ride-along?outlet=" + OUTLET + "&requestedDate=" + serviceDate, 200));
    assertFalse(fresh.get("offered").asBoolean());

    // Another store's hint is 403 plus audit, never an empty answer.
    UUID strangerId = userId(strangerEmail);
    long before = denials(strangerId, "order:Read");
    read(stranger, path, 403);
    assertEquals(before + 1, denials(strangerId, "order:Read"));
  }

  // ---- helpers -------------------------------------------------------------

  private String placeFor(UUID commandId, String outlet, LocalDate requested) {
    return envelope(commandId, "order:Place", null,
        "{\"outletId\":\"" + outlet + "\",\"requestedDate\":\"" + requested
            + "\",\"lines\":[{\"productId\":\"P-1\",\"quantity\":12}]}");
  }

  private UUID placed() throws Exception {
    JsonNode result = mapper.readTree(send(manager, place(UUID.randomUUID(), serviceDate), 200)).get("result");
    return UUID.fromString(result.get("orderId").asText());
  }

  private static Instant at(LocalDate date, LocalTime time) {
    return date.atTime(time).atZone(Clock.OPERATING_ZONE).toInstant();
  }

  /**
   * Sessions end by the same clock the cutoff reads, so a jump past their idle
   * lifetime signs everyone out. Each person signs in again at the new time.
   */
  private void moveTo(Instant instant) {
    clock.set(instant);
    manager = session(managerEmail);
    stranger = session(strangerEmail);
    dispatcher = session(dispatcherEmail);
  }

  private Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  private String place(UUID commandId, LocalDate requested) {
    return envelope(commandId, "order:Place", null,
        "{\"outletId\":\"" + OUTLET + "\",\"requestedDate\":\"" + requested
            + "\",\"lines\":[{\"productId\":\"P-1\",\"quantity\":12}]}");
  }

  private String amend(UUID commandId, UUID orderId, Long version, int quantity) {
    return envelope(commandId, "order:Amend", version,
        "{\"orderId\":\"" + orderId + "\",\"lines\":[{\"productId\":\"P-1\",\"quantity\":" + quantity + "}]}");
  }

  private static String envelope(UUID commandId, String kind, Long version, String payload) {
    return """
        {"commandId":"%s","kind":"%s","expectedVersion":%s,"payload":%s,
         "clientRecordedAt":"2026-09-27T08:00:00Z"}
        """
        .formatted(commandId, kind, version == null ? "null" : version.toString(), payload);
  }

  private Command command(UUID id, String kind, Long version, String payload) throws Exception {
    return new Command(id, kind, version, mapper.readTree(payload), Instant.now());
  }

  private String send(Cookie session, String body, int expected) throws Exception {
    MvcResult result =
        http.perform(
                post("/api/commands")
                    .cookie(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expected, result.getResponse().getStatus(), response);
    return response;
  }

  private String read(Cookie session, String path, int expected) throws Exception {
    MvcResult result = http.perform(get(path).cookie(session)).andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expected, result.getResponse().getStatus(), response);
    return response;
  }

  private List<Map<String, Object>> outbox(UUID orderId) {
    return database.asModule(
        ModuleRole.INTEGRATION,
        null,
        () ->
            database.query(
                "SELECT * FROM integration.outbox_events WHERE aggregate_id = ? ORDER BY occurred_at, event_id",
                orderId.toString()));
  }

  private UUID userId(String email) {
    return (UUID)
        database
            .asModule(
                ModuleRole.IAM,
                null,
                () -> database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", email))
            .get("user_id");
  }

  private long denials(UUID actorId, String action) {
    return count(ModuleRole.INTEGRATION,
        "SELECT count(*) AS n FROM integration.audit_log WHERE actor_id = ? AND action = ? AND decision = 'DENY'",
        actorId, action);
  }

  private long count(ModuleRole role, String sql, Object... params) {
    return ((Number) database.asModule(role, null, () -> database.queryOne(sql, params)).get("n"))
        .longValue();
  }
}
