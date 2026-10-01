package com.waypoint.dispatch.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.ordering.application.OrderDataQuery;
import com.waypoint.dispatch.ordering.contract.OrderQuery;
import com.waypoint.dispatch.ordering.contract.OrderStatus;
import com.waypoint.dispatch.ordering.contract.OrderViews.OrderView;
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
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.domain.Page;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * The ordering schema against a real PostgreSQL: row-level security decides
 * who sees which order, the version guard refuses a stale write, and reads are
 * keyset-paginated.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class OrderingSchemaIntegrationTest {
  static final String OUTLET = "OUT001";
  static final String PASSWORD = "ordering-schema-test-password";

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired ImportReferenceDataHandler referenceImport;
  @Autowired ReferenceQuery reference;
  @Autowired AccountAdminUseCase accounts;
  @Autowired JdbcOrderRepository orders;
  @Autowired OrderDataQuery query;
  @Autowired OrderQuery contract;

  String depot;
  Actor manager;
  Actor stranger;
  Actor dispatcher;

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
    depot = reference.outlet(OUTLET, null).orElseThrow().depotCode();
    String run = UUID.randomUUID().toString().substring(0, 8);
    manager = Actor.user(accounts.createAccount("mgr-" + run + "@ordering.test", "Manager", PASSWORD, "store_manager"));
    stranger = Actor.user(accounts.createAccount("far-" + run + "@ordering.test", "Stranger", PASSWORD, "store_manager"));
    String dispatcherEmail = "dsp-" + run + "@ordering.test";
    dispatcher = Actor.user(accounts.createAccount(dispatcherEmail, "Dispatcher", PASSWORD, "dispatcher"));
    accounts.grantDepot(dispatcherEmail, depot);
    database.asModule(
        ModuleRole.IAM,
        null,
        () ->
            database.update(
                "INSERT INTO iam.user_outlet_access (user_id, outlet_id) VALUES (?, ?)",
                manager.userId(),
                OUTLET));
  }

  @Test
  void theOutletsManagerSeesTheOrderAndNoOneElseDoes() {
    Order order = saved(freshDate(), true);

    assertEquals(order.orderRef(), query.order(manager, order.orderId()).orderRef());
    DomainException hidden =
        assertThrows(DomainException.class, () -> query.order(stranger, order.orderId()));
    assertEquals(ErrorCode.NOT_FOUND, hidden.code(), "out of scope looks like absent, not forbidden");
    assertEquals(
        order.orderRef(), query.order(dispatcher, order.orderId()).orderRef(), "depot scope reads it");
  }

  @Test
  void aListOutsideScopeIsForbiddenAndAuditedNeverEmpty() {
    long before = deniedReads(stranger);
    DomainException denied =
        assertThrows(
            DomainException.class,
            () -> query.ordersForOutlet(stranger, OUTLET, Optional.empty(), 10));
    assertEquals(ErrorCode.FORBIDDEN, denied.code());
    assertEquals(before + 1, deniedReads(stranger));
  }

  @Test
  void aContractReadWithNoActorSeesNothing() {
    Order order = saved(freshDate(), true);
    assertTrue(contract.order(order.orderId()).isEmpty());
  }

  @Test
  void aContractReadInsideAnotherModulesTransactionUsesThatActor() {
    LocalDate date = freshDate();
    Order reserved = saved(date, true);
    saved(date, false);

    List<UUID> demand =
        database.asModule(
            ModuleRole.PLANNING,
            dispatcher.userId(),
            () ->
                contract.confirmedDemand(depot, date).stream()
                    .map(d -> d.orderId())
                    .toList());

    assertEquals(List.of(reserved.orderId()), demand, "stock-unknown is never demand (R-STK-05)");
  }

  @Test
  void aStaleVersionIsRefused() {
    Order order = saved(freshDate(), true);
    database.asSystem(
        ModuleRole.ORDERING,
        () -> orders.update(order.moveTo(OrderStatus.ALLOCATED), 1, false, Instant.now()));

    DomainException stale =
        assertThrows(
            DomainException.class,
            () ->
                database.asSystem(
                    ModuleRole.ORDERING,
                    () -> orders.update(order.moveTo(OrderStatus.DEFERRED), 1, false, Instant.now())));
    assertEquals(ErrorCode.VERSION_CONFLICT, stale.code());
  }

  @Test
  void amendedLinesAreANewRevisionAndTheOldOnesRemain() {
    Order order = saved(freshDate(), true);
    Order amended = order.amend(List.of(new OrderLine("P-2", 7)), Optional.empty());
    database.asSystem(
        ModuleRole.ORDERING, () -> orders.update(amended, 1, true, Instant.now()));

    OrderView view = query.order(manager, order.orderId());
    assertEquals(1, view.lines().size());
    assertEquals("P-2", view.lines().get(0).productId());
    assertEquals(2, view.rowVersion());
    long revisions =
        database.asSystem(
            ModuleRole.ORDERING,
            () ->
                ((Number)
                        database
                            .queryOne(
                                "SELECT count(DISTINCT revision) AS n FROM ordering.order_lines WHERE order_id = ?",
                                order.orderId())
                            .get("n"))
                    .longValue());
    assertEquals(2, revisions);
  }

  @Test
  void aManagerCannotWriteAnOrderForAnotherOutlet() {
    Order order = order(freshDate(), true);
    assertThrows(
        RuntimeException.class,
        () ->
            database.asModule(
                ModuleRole.ORDERING,
                stranger.userId(),
                () -> orders.insert(order, stranger.userId(), Instant.now(), UUID.randomUUID(), Optional.empty())));
  }

  @Test
  void ordersArePagedNewestFirstOnAKeyset() {
    for (int i = 0; i < 3; i++) {
      saved(freshDate(), true);
    }
    Page<OrderView> first = query.ordersForOutlet(manager, OUTLET, Optional.empty(), 2);
    assertEquals(2, first.items().size());
    assertTrue(first.nextCursor().isPresent());
    Page<OrderView> second = query.ordersForOutlet(manager, OUTLET, first.nextCursor(), 2);
    assertFalse(second.items().isEmpty());
    OrderView lastOfFirst = first.items().get(1);
    OrderView firstOfSecond = second.items().get(0);
    assertFalse(lastOfFirst.orderId().equals(firstOfSecond.orderId()));
    assertFalse(firstOfSecond.placedAt().isAfter(lastOfFirst.placedAt()));
  }

  @Test
  void aClosedDayIsVisibleToAStoreManagerButOnlyADispatcherCanCloseIt() {
    LocalDate date = freshDate();
    assertThrows(
        RuntimeException.class,
        () ->
            database.asModule(
                ModuleRole.ORDERING,
                manager.userId(),
                () -> orders.close(depot, date, manager.userId(), 0, Instant.now())));
    database.asModule(
        ModuleRole.ORDERING,
        dispatcher.userId(),
        () -> orders.close(depot, date, dispatcher.userId(), 0, Instant.now()));

    assertTrue(
        database.asModule(ModuleRole.ORDERING, manager.userId(), () -> orders.isClosed(depot, date)));
  }

  // ---- fixtures ------------------------------------------------------------

  private Order saved(LocalDate date, boolean reserved) {
    Order order = order(date, reserved);
    database.asSystem(
        ModuleRole.ORDERING,
        () -> orders.insert(order, manager.userId(), Instant.now(), UUID.randomUUID(), Optional.empty()));
    return order;
  }

  private Order order(LocalDate date, boolean reserved) {
    var outlet = reference.outlet(OUTLET, null).orElseThrow();
    String ref = "WPO-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 11).toUpperCase();
    return Order.place(
        UUID.randomUUID(),
        ref,
        OUTLET,
        outlet.depotCode(),
        outlet.brandCode(),
        outlet.districtName(),
        new DeliveryDate(date, date, List.of()),
        reserved
            ? Optional.of(
                new Reservation("WH-" + ref, new BigDecimal("120.500"), new BigDecimal("0.8000"), "ambient", 12))
            : Optional.empty(),
        List.of(new OrderLine("P-1", 3)));
  }

  private static LocalDate freshDate() {
    return LocalDate.of(2031, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 20_000));
  }

  private long deniedReads(Actor actor) {
    return database.asModule(
        ModuleRole.INTEGRATION,
        actor.userId(),
        () ->
            ((Number)
                    database
                        .queryOne(
                            "SELECT count(*) AS n FROM integration.audit_log"
                                + " WHERE actor_id = ? AND action = 'order:Read' AND decision = 'DENY'",
                            actor.userId())
                        .get("n"))
                .longValue());
  }
}
