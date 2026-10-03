package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What every Planning integration test needs: a migrated database with the
 * reference data, a dispatcher for the test depot and one for another depot, a
 * street-access outlet, a far-future operating day, and helpers that speak to
 * the API the way a client does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
abstract class PlanningIntegrationSupport {
  static final String PASSWORD = "PlanningCommands2026!";

  @Autowired MockMvc http;
  @Autowired ObjectMapper mapper;
  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired JdbcOrderRepository orders;

  String depot;
  String otherDepot;
  OutletView outlet;
  LocalDate serviceDate;
  Cookie dispatcher;
  String dispatcherEmail;
  Cookie elsewhere;
  String elsewhereEmail;

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
  void setUpPlanning() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    depot = reference.outlet("OUT001", null).orElseThrow().depotCode();
    otherDepot =
        database.asSystem(
                ModuleRole.PLANNING,
                () ->
                    (String)
                        database
                            .queryOne(
                                "SELECT depot_code FROM ref.depots WHERE reference_version_id = ?"
                                    + " AND depot_code <> ? ORDER BY depot_code LIMIT 1",
                                reference.currentVersionId().orElseThrow(),
                                depot)
                            .get("depot_code"));
    // A street-access outlet with a window, so an ordinary truck can serve it.
    outlet =
        reference.outletsOfDepot(depot, null).stream()
            .filter(o -> !o.vanOnly() && o.effectiveWindowOpen().isPresent())
            .min(Comparator.comparing(OutletView::outletId))
            .orElseThrow();
    serviceDate =
        reference.nextOperatingDay(
            LocalDate.of(2045, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 15_000)));

    String run = UUID.randomUUID().toString().substring(0, 8);
    String here = "pcd-" + run + "@planning.test";
    elsewhereEmail = "pce-" + run + "@planning.test";
    accounts.createAccount(here, "Dispatcher", PASSWORD, "dispatcher");
    accounts.grantDepot(here, depot);
    accounts.createAccount(elsewhereEmail, "Elsewhere", PASSWORD, "dispatcher");
    accounts.grantDepot(elsewhereEmail, otherDepot);
    dispatcherEmail = here;
    dispatcher = session(here);
    elsewhere = session(elsewhereEmail);
  }

  // ---- fixtures ------------------------------------------------------------

  /** A confirmed, reserved order for the test outlet on the service date. */
  UUID demand(String temperature) {
    UUID id = UUID.randomUUID();
    String ref = "WPO-P" + id.toString().replace("-", "").substring(0, 11).toUpperCase();
    Order order =
        Order.place(
            id,
            ref,
            outlet.outletId(),
            depot,
            outlet.brandCode(),
            outlet.districtName(),
            new DeliveryDate(serviceDate, serviceDate, List.of()),
            Optional.of(
                new Reservation("WH-" + ref, new BigDecimal("80.000"), new BigDecimal("0.6000"), temperature, 6)),
            List.of(new OrderLine("P-1", 6)));
    database.asSystem(
        ModuleRole.ORDERING,
        () -> orders.insert(order, null, Instant.now(), UUID.randomUUID(), Optional.empty()));
    return id;
  }

  JsonNode generate(Cookie session, int expected) throws Exception {
    String body = send(session, envelope("plan:Generate", null, generatePayload()), expected);
    return expected == 200 ? mapper.readTree(body).get("result") : mapper.readTree(body);
  }

  String generatePayload() {
    return "{\"depotCode\":\"" + depot + "\",\"serviceDate\":\"" + serviceDate + "\"}";
  }

  static String publish(UUID planId, Long version) {
    return envelope("plan:Publish", version, "{\"planId\":\"" + planId + "\"}");
  }

  static String defer(UUID planId, Long version, UUID orderId, String reason) {
    return envelope(
        "plan:Defer", version,
        "{\"planId\":\"" + planId + "\",\"orderId\":\"" + orderId + "\",\"reason\":\"" + reason + "\"}");
  }

  static String override(UUID planId, Long version, UUID orderId, String vehicle, int trip, String reason) {
    return envelope(
        "plan:Override", version,
        "{\"planId\":\"" + planId + "\",\"orderId\":\"" + orderId + "\",\"vehicleId\":\"" + vehicle
            + "\",\"tripNumber\":" + trip + ",\"reason\":\"" + reason + "\"}");
  }

  static String envelope(String kind, Long version, String payload) {
    return """
        {"commandId":"%s","kind":"%s","expectedVersion":%s,"payload":%s,
         "clientRecordedAt":"2026-10-01T08:00:00Z"}
        """
        .formatted(UUID.randomUUID(), kind, version == null ? "null" : version.toString(), payload);
  }

  Set<UUID> allocatedOrders(JsonNode plan) {
    return StreamSupport.stream(plan.get("allocations").spliterator(), false)
        .map(a -> UUID.fromString(a.get("orderId").asText()))
        .collect(Collectors.toSet());
  }

  String vehicleOf(UUID planId, UUID orderId) throws Exception {
    JsonNode plan = mapper.readTree(read(dispatcher, "/api/plans/" + planId, 200));
    for (JsonNode trip : plan.get("trips")) {
      for (JsonNode stop : trip.get("stops")) {
        if (stop.get("orderId").asText().equals(orderId.toString())) {
          assertEquals(1, trip.get("tripNumber").asInt(), "the fixture expects the order on a first trip");
          return trip.get("vehicleId").asText();
        }
      }
    }
    throw new AssertionError("order " + orderId + " was not served: " + plan);
  }

  Set<String> vehiclesOf(UUID planId) throws Exception {
    JsonNode plan = mapper.readTree(read(dispatcher, "/api/plans/" + planId, 200));
    return StreamSupport.stream(plan.get("trips").spliterator(), false)
        .map(t -> t.get("vehicleId").asText())
        .collect(Collectors.toSet());
  }

  Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }

  String send(Cookie session, String body, int expected) throws Exception {
    MvcResult result =
        http.perform(post("/api/commands").cookie(session).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expected, result.getResponse().getStatus(), response);
    return response;
  }

  String read(Cookie session, String path, int expected) throws Exception {
    MvcResult result = http.perform(get(path).cookie(session)).andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expected, result.getResponse().getStatus(), response);
    return response;
  }

  long outbox(String aggregateId, String type) {
    return outboxRows(aggregateId, type).size();
  }

  List<Map<String, Object>> outboxRows(String aggregateId, String type) {
    return database.asModule(
        ModuleRole.INTEGRATION,
        null,
        () ->
            database.query(
                "SELECT event_type, payload::text AS payload FROM integration.outbox_events"
                    + " WHERE aggregate_id = ? AND event_type = ?",
                aggregateId,
                type));
  }

  UUID userId(String email) {
    return (UUID)
        database
            .asModule(
                ModuleRole.IAM,
                null,
                () -> database.queryOne("SELECT user_id FROM iam.users WHERE email = ?", email))
            .get("user_id");
  }

  long denials(UUID actorId, String action) {
    return ((Number)
            database
                .asModule(
                    ModuleRole.INTEGRATION,
                    null,
                    () ->
                        database.queryOne(
                            "SELECT count(*) AS n FROM integration.audit_log"
                                + " WHERE actor_id = ? AND action = ? AND decision = 'DENY'",
                            actorId,
                            action))
                .get("n"))
        .longValue();
  }
}
