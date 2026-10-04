package com.waypoint.dispatch.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.execution.contract.ExecutionEvents.DeliveryCompleted;
import com.waypoint.dispatch.execution.contract.ExecutionViews.DeliveryOutcome;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.domain.DeliveryDate;
import com.waypoint.dispatch.ordering.domain.Order;
import com.waypoint.dispatch.ordering.domain.OrderLine;
import com.waypoint.dispatch.ordering.domain.Reservation;
import com.waypoint.dispatch.ordering.infrastructure.JdbcOrderRepository;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.ConsumerInbox;
import com.waypoint.dispatch.platform.messaging.EventSubscriber;
import com.waypoint.dispatch.referencedata.application.ImportReferenceDataHandler;
import com.waypoint.dispatch.referencedata.contract.ReferenceQuery;
import com.waypoint.dispatch.referencedata.contract.ReferenceViews.OutletView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What every Receipt and Issues integration test needs: a migrated database with
 * reference data, people of each role scoped to one outlet and its depot plus a
 * stranger elsewhere, orders written straight into Ordering, and helpers that
 * speak to the API and stand in for the event relay the way #6 will.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
public abstract class ReceiptIssuesSupport {
  protected static final String PASSWORD = "ReceiptIssues2026!";

  @Autowired protected MockMvc http;
  @Autowired protected ObjectMapper mapper;
  @Autowired protected Migrator migrator;
  @Autowired protected Database database;
  @Autowired protected AccountAdminUseCase accounts;
  @Autowired protected LoginHandler login;
  @Autowired protected ImportReferenceDataHandler referenceImport;
  @Autowired protected ReferenceQuery reference;
  @Autowired protected JdbcOrderRepository orders;
  @Autowired protected ConsumerInbox inbox;
  @Autowired protected List<EventSubscriber<?>> subscribers;

  protected OutletView outlet;
  protected OutletView otherOutlet;
  protected String depot;
  protected String otherDepot;
  protected Person manager;
  protected Person stranger;
  protected Person dispatcher;
  protected Person farDispatcher;
  protected Person driver;
  /** A driver never assigned a vehicle: no scope at all, sees only what they raised. */
  protected Person roamingDriver;
  protected Person loader;

  /** An account with its id and a live session. */
  protected record Person(UUID id, String email, Cookie session) {
    public Actor actor() {
      return Actor.user(id);
    }
  }

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
    // The auto-close and escalation jobs are run at chosen instants here; the
    // real scheduler firing on a quarter hour mid-test would close receipts early.
    registry.add("app.scheduling.enabled", () -> "false");
  }

  @BeforeEach
  void setUpReceiptIssues() {
    migrator.migrate();
    referenceImport.importFrom(Path.of("../data"), null);
    List<OutletView> all =
        reference.outletsOfDepot(reference.outlet("OUT001", null).orElseThrow().depotCode(), null).stream()
            .sorted(Comparator.comparing(OutletView::outletId))
            .toList();
    outlet = all.get(0);
    depot = outlet.depotCode();
    otherDepot =
        database.asSystem(
            ModuleRole.RECEIPT,
            () ->
                (String)
                    database
                        .queryOne(
                            "SELECT depot_code FROM ref.depots WHERE reference_version_id = ? AND depot_code <> ?"
                                + " ORDER BY depot_code LIMIT 1",
                            reference.currentVersionId().orElseThrow(),
                            depot)
                        .get("depot_code"));
    otherOutlet = reference.outletsOfDepot(otherDepot, null).get(0);

    String run = UUID.randomUUID().toString().substring(0, 8);
    manager = person("rim-" + run, "store_manager", Optional.empty(), Optional.of(outlet.outletId()));
    stranger = person("ris-" + run, "store_manager", Optional.empty(), Optional.of(otherOutlet.outletId()));
    dispatcher = person("rid-" + run, "dispatcher", Optional.of(depot), Optional.empty());
    farDispatcher = person("rif-" + run, "dispatcher", Optional.of(otherDepot), Optional.empty());
    driver = person("rdr-" + run, "driver", Optional.empty(), Optional.empty());
    roamingDriver = person("rrd-" + run, "driver", Optional.empty(), Optional.empty());
    loader = person("rlo-" + run, "loader", Optional.of(depot), Optional.empty());
  }

  private Person person(String prefix, String role, Optional<String> depotScope, Optional<String> outletScope) {
    String email = prefix + "@receipt-issues.test";
    UUID id = accounts.createAccount(email, prefix, PASSWORD, role);
    depotScope.ifPresent(d -> accounts.grantDepot(email, d));
    outletScope.ifPresent(
        o ->
            database.asModule(
                ModuleRole.IAM,
                null,
                () ->
                    database.update(
                        "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)", id, o)));
    Cookie session = new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
    return new Person(id, email, session);
  }

  /**
   * Assigns {@code who} to a vehicle of the test depot for today, the way a
   * driver gets scope (R-IAM-13). A vehicle takes one driver per date, and the
   * test database is shared, so this picks one no one drives today.
   */
  protected String assignToday(Person who) {
    LocalDate today = LocalDate.now(com.waypoint.dispatch.shared.util.Clock.OPERATING_ZONE);
    String vehicle =
        database.asModule(
            ModuleRole.IAM,
            null,
            () ->
                (String)
                    Optional.ofNullable(
                            database.queryOne(
                                "SELECT v.vehicle_id FROM ref.vehicles v WHERE v.reference_version_id = ?"
                                    + " AND v.depot_code = ? AND NOT EXISTS (SELECT 1 FROM"
                                    + " iam.vehicle_driver_assignments a WHERE a.vehicle_id = v.vehicle_id"
                                    + " AND a.validity @> ?::date) ORDER BY v.vehicle_id LIMIT 1",
                                reference.currentVersionId().orElseThrow(),
                                depot,
                                java.sql.Date.valueOf(today)))
                        .orElseThrow(
                            () -> new IllegalStateException(
                                "every vehicle of " + depot + " already has a driver today; recreate the test database"))
                        .get("vehicle_id"));
    database.asModule(
        ModuleRole.IAM,
        null,
        () ->
            database.update(
                "INSERT INTO iam.vehicle_driver_assignments (vehicle_id, driver_user_id, validity)"
                    + " VALUES (?, ?, daterange(?, ?))",
                vehicle,
                who.id(),
                java.sql.Date.valueOf(today),
                java.sql.Date.valueOf(today.plusDays(1))));
    return vehicle;
  }

  // ---- orders and deliveries -------------------------------------------------

  /** A reserved order with two lines, already delivered, written straight into Ordering. */
  protected Order deliveredOrder(OutletView at) {
    LocalDate date = TestDates.unusedDay();
    String ref = "WPO-R" + UUID.randomUUID().toString().replace("-", "").substring(0, 11).toUpperCase();
    Order placed =
        Order.place(
            UUID.randomUUID(), ref, at.outletId(), at.depotCode(), at.brandCode(), at.districtName(),
            new DeliveryDate(date, date, List.of()),
            Optional.of(new Reservation("WH-" + ref, new BigDecimal("80.000"), new BigDecimal("0.6000"), "ambient", 14)),
            List.of(new OrderLine("P-1", 10), new OrderLine("P-2", 4)));
    database.asSystem(
        ModuleRole.ORDERING,
        () -> {
          orders.insert(placed, null, Instant.now(), UUID.randomUUID(), Optional.empty());
          orders.update(placed.moveTo(OrderStatus.DELIVERED), 1, false, Instant.now());
        });
    return placed;
  }

  protected OrderStatus orderStatus(UUID orderId) {
    return database.asSystem(ModuleRole.ORDERING, () -> orders.find(orderId).orElseThrow().status());
  }

  protected DeliveryCompleted completed(Order order, Instant at) {
    return new DeliveryCompleted(
        UUID.randomUUID(), order.orderId(), UUID.randomUUID(), order.outletId(), DeliveryOutcome.DELIVERED,
        Optional.of(14), at, Optional.empty());
  }

  // ---- the relay, as #6 will run it ----------------------------------------------

  /** One delivery of an event to one consumer, in the consumer's own transaction, claimed in its inbox. */
  protected <E extends DomainEvent> UUID deliver(String consumer, E event) {
    return redeliver(consumer, UUID.randomUUID(), event, Optional.empty());
  }

  protected <E extends DomainEvent> UUID deliver(String consumer, E event, UUID actorId) {
    return redeliver(consumer, UUID.randomUUID(), event, Optional.of(actorId));
  }

  @SuppressWarnings("unchecked")
  protected <E extends DomainEvent> UUID redeliver(String consumer, UUID eventId, E event, Optional<UUID> actorId) {
    EventSubscriber<E> subscriber =
        (EventSubscriber<E>)
            subscribers.stream().filter(s -> s.consumerName().equals(consumer)).findFirst().orElseThrow();
    EventEnvelope<E> envelope =
        new EventEnvelope<>(eventId, event.type(), 1, Instant.now(), "test", Optional.empty(), actorId, event);
    database.asSystem(
        subscriber.moduleRole(),
        () -> {
          if (inbox.claim(consumer, eventId)) {
            subscriber.on(envelope);
          }
        });
    return eventId;
  }

  // ---- the API ---------------------------------------------------------------------

  protected static String envelope(String kind, Long version, String payload) {
    return """
        {"commandId":"%s","kind":"%s","expectedVersion":%s,"payload":%s,
         "clientRecordedAt":"2026-10-02T08:00:00Z"}
        """
        .formatted(UUID.randomUUID(), kind, version == null ? "null" : version.toString(), payload);
  }

  protected JsonNode send(Person who, String body, int expected) throws Exception {
    MvcResult result =
        http.perform(post("/api/commands").cookie(who.session()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expected, result.getResponse().getStatus(), response);
    return mapper.readTree(response);
  }

  protected JsonNode read(Person who, String path, int expected) throws Exception {
    MvcResult result = http.perform(get(path).cookie(who.session())).andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expected, result.getResponse().getStatus(), response);
    return response.isEmpty() ? mapper.nullNode() : mapper.readTree(response);
  }

  protected List<Map<String, Object>> outbox(String aggregateId, String type) {
    return database.asModule(
        ModuleRole.INTEGRATION,
        null,
        () ->
            database.query(
                "SELECT event_type, payload::text AS payload FROM integration.outbox_events"
                    + " WHERE aggregate_id = ? AND event_type = ? ORDER BY occurred_at",
                aggregateId,
                type));
  }

  protected long denials(UUID actorId, String action) {
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
