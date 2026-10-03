package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.identity.application.AccountAdminUseCase;
import com.waypoint.dispatch.identity.application.LoginHandler;
import com.waypoint.dispatch.identity.web.AuthController;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrdersClosed;
import com.waypoint.dispatch.platform.config.RelayProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.support.TestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The delivery half of the event backbone against a real PostgreSQL (issue #6).
 *
 * <p>Each test builds its own relay over recording subscribers and a clock it
 * moves by hand, so backoff and leases are exercised without waiting. The
 * subscribers consume a real event, {@code orders.closed}, and ignore every
 * depot but the one the test invented, because the outbox is shared with every
 * other test class in the run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestDatabase.class)
class OutboxRelayIntegrationTest {
  private static final LocalDate DAY = LocalDate.parse("2027-03-01");
  private static final String PASSWORD = "RelayReplay2026!";

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired EventPublisher publisher;
  @Autowired ConsumerInbox inbox;
  @Autowired ObjectMapper mapper;
  @Autowired Metrics metrics;
  @Autowired RelaySignal signal;
  @Autowired OutboxRelay wired;
  @Autowired List<EventSubscriber<?>> wiredSubscribers;
  @Autowired MockMvc http;
  @Autowired AccountAdminUseCase accounts;
  @Autowired LoginHandler login;

  /** Unique per test, so reruns on the same database never meet old rows. */
  private final String tag = "R" + UUID.randomUUID().toString().substring(0, 8);

  private final AtomicReference<Instant> now = new AtomicReference<>();

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeEach
  void startFromAnEmptyQueue() {
    migrator.migrate();
    // Other test classes leave their events pending. Settle them, or every test
    // here would first hand strangers' events to its relay.
    bookkeeping(
        "UPDATE integration.outbox_events SET status = 'published'"
            + " WHERE status IN ('pending','failed','processing')");
    // Rows are stamped by the database clock; a second ahead keeps every new row due.
    now.set(Instant.now().plusSeconds(1));
  }

  @Test
  void anEventReachesEverySubscriberWithItsEnvelopeAndIsMarkedPublished() {
    Recording first = new Recording("first");
    Recording second = new Recording("second");
    Actor actor = Actor.user(UUID.randomUUID());
    database.asModule(ModuleRole.ORDERING, actor.userId(), () -> publisher.publish(actor, closed("A")));

    drain(relay(8, first, second));

    assertEquals(1, first.seen.size());
    assertEquals(1, second.seen.size());
    EventEnvelope<OrdersClosed> envelope = first.seen.get(0);
    assertEquals(OrdersClosed.TYPE, envelope.type());
    assertEquals("ordering", envelope.producer());
    assertEquals(actor.userId(), envelope.actorId().orElseThrow());
    assertEquals(tag + "A", envelope.payload().depotCode());
    assertEquals(DAY, envelope.payload().serviceDate());
    assertEquals(envelope.eventId(), eventId("A"));
    assertEquals("published", status("A"));
  }

  @Test
  void anEventFromARolledBackTransactionIsNeverDelivered() {
    Recording subscriber = new Recording("only");

    assertThrows(
        IllegalStateException.class,
        () ->
            database.asSystem(
                ModuleRole.ORDERING,
                () -> {
                  publisher.publish(Actor.SYSTEM, closed("A"));
                  throw new IllegalStateException("the change failed after publishing");
                }));
    drain(relay(8, subscriber));

    assertTrue(subscriber.seen.isEmpty(), "an event that never committed announces something that did not happen");
  }

  @Test
  void aRedeliveryAppliesOncePerConsumer() {
    Recording healthy = new Recording("healthy");
    Recording flaky = new Recording("flaky");
    flaky.failing = event -> true;
    publish("A");
    OutboxRelay relay = relay(8, healthy, flaky);

    drain(relay);
    assertEquals(1, healthy.seen.size());
    assertEquals(0, flaky.seen.size());
    assertEquals("failed", status("A"));
    assertEquals(1, attempts("A"));
    assertTrue(lastError("A").contains(flaky.consumerName()), lastError("A"));

    // Not due yet: the backoff has not passed.
    assertEquals(0, relay.deliverBatch());

    flaky.failing = event -> false;
    now.set(now.get().plus(Duration.ofSeconds(10)));
    drain(relay);

    assertEquals(1, healthy.seen.size(), "the consumer that had already applied it must not run again");
    assertEquals(1, healthy.calls, "not even entered: the inbox answers before the consumer does");
    assertEquals(1, flaky.seen.size());
    assertEquals("published", status("A"));
    assertEquals(2, attempts("A"));
  }

  @Test
  void twoRelaysNeverClaimTheSameEvent() {
    int events = 60;
    for (int i = 0; i < events; i++) {
      publish("N" + i);
    }
    // Each event is due from the database time it was written. Sixty writes can
    // outlast the one second set before the test on a slow runner, so move the
    // relays' clock past the last of them.
    now.set(Instant.now().plusSeconds(1));
    // Different consumer names, so the inbox cannot hide a double claim: an
    // event claimed by both relays would be seen by both recordings.
    Recording one = new Recording("one");
    Recording two = new Recording("two");
    OutboxRelay relayOne = relay(8, 7, one);
    OutboxRelay relayTwo = relay(8, 7, two);

    CompletableFuture<Void> first = CompletableFuture.runAsync(() -> drain(relayOne));
    CompletableFuture<Void> second = CompletableFuture.runAsync(() -> drain(relayTwo));
    first.join();
    second.join();
    drain(relayOne);

    Set<UUID> seen = new HashSet<>();
    one.seen.forEach(e -> assertTrue(seen.add(e.eventId())));
    two.seen.forEach(e -> assertTrue(seen.add(e.eventId()), "delivered by both relays: " + e.eventId()));
    assertEquals(events, seen.size());
    long attemptedMoreThanOnce =
        count(
            "SELECT count(*) AS n FROM integration.outbox_events"
                + " WHERE aggregate_id LIKE ? AND (attempts <> 1 OR status <> 'published')",
            tag + "N%");
    assertEquals(0L, attemptedMoreThanOnce);
  }

  @Test
  void oneAggregatesEventsArriveInWriteOrderEvenAcrossAFailure() {
    UUID firstOrder = UUID.randomUUID();
    UUID secondOrder = UUID.randomUUID();
    database.asSystem(
        ModuleRole.ORDERING,
        () -> {
          // Same aggregate, same transaction, same millisecond.
          publisher.publish(Actor.SYSTEM, closed("A", firstOrder));
          publisher.publish(Actor.SYSTEM, closed("A", secondOrder));
        });
    publish("B");
    Recording subscriber = new Recording("ordered");
    subscriber.failing = event -> event.orderIds().contains(firstOrder);
    OutboxRelay relay = relay(8, subscriber);

    drain(relay);
    assertEquals(
        List.of(tag + "B"),
        subscriber.depots(),
        "the later event of the failing aggregate waits; another aggregate does not");

    subscriber.failing = event -> false;
    now.set(now.get().plus(Duration.ofSeconds(10)));
    drain(relay);

    assertEquals(
        List.of(firstOrder, secondOrder),
        subscriber.seen.stream()
            .filter(e -> e.payload().depotCode().equals(tag + "A"))
            .map(e -> e.payload().orderIds().get(0))
            .toList());
  }

  @Test
  void aClaimAbandonedByADeadRelayIsTakenAgainWhenItsLeaseLapses() {
    publish("A");
    publish("B");
    // What a relay that crashed mid-batch leaves behind (PLT-02).
    abandon("A", now.get().minusSeconds(1));
    abandon("B", now.get().plus(Duration.ofMinutes(4)));
    Recording subscriber = new Recording("survivor");

    drain(relay(8, subscriber));

    assertEquals(List.of(tag + "A"), subscriber.depots());
    assertEquals("published", status("A"));
    assertEquals("processing", status("B"), "a live lease belongs to whoever holds it");
  }

  @Test
  void aPoisonEventIsDeadLetteredWithoutBlockingTheQueueAndCanBeReplayed() throws Exception {
    publish("A");
    Recording subscriber = new Recording("poisoned");
    subscriber.failing = event -> event.depotCode().equals(tag + "A");
    OutboxRelay relay = relay(2, subscriber);

    drain(relay);
    publish("B");
    now.set(now.get().plus(Duration.ofMinutes(10)));
    drain(relay);

    assertEquals("dead", status("A"));
    assertEquals(2, attempts("A"));
    assertTrue(lastError("A").contains("refused"), lastError("A"));
    assertEquals(List.of(tag + "B"), subscriber.depots(), "a poison event never blocks the queue");
    assertEquals(0, relay.deliverBatch(), "and is not retried for ever");
    assertTrue(relay.backlog().dead() >= 1);

    UUID eventId = eventId("A");
    String admin = account("admin");
    String dispatcher = account("dispatcher");

    // The list names the event and why it failed, and never carries the payload.
    JsonNode page = mapper.readTree(read(admin, "/api/platform/events/dead?limit=200", 200));
    JsonNode listed = null;
    for (JsonNode item : page.get("items")) {
      if (item.get("eventId").asText().equals(eventId.toString())) {
        listed = item;
      }
    }
    assertNotNull(listed, "the dead letter must be listed: " + page);
    assertEquals(OrdersClosed.TYPE, listed.get("type").asText());
    assertFalse(listed.has("payload"));
    read(dispatcher, "/api/platform/events/dead", 403);

    // Replay is an administrator's command.
    send(dispatcher, replay(eventId), 403);
    assertEquals("dead", status("A"));
    send(admin, replay(eventId), 200);
    assertEquals("pending", status("A"));
    assertEquals(0, attempts("A"));

    subscriber.failing = event -> false;
    now.set(Instant.now().plusSeconds(1));
    drain(relay);
    assertEquals("published", status("A"));
    assertEquals(List.of(tag + "B", tag + "A"), subscriber.depots());

    // Only a dead letter can be replayed.
    send(admin, replay(eventId), 409);
    send(admin, replay(UUID.randomUUID()), 404);
  }

  @Test
  void anEventNobodySubscribesToIsSettledRatherThanLeftPending() {
    publish("A");

    drain(relay(8));

    assertEquals("published", status("A"));
  }

  @Test
  void theWorkerDeliversACommittedEventWithoutWaitingForThePoll() throws Exception {
    // A poll interval far longer than the test: only the publisher's signal
    // after commit can explain a prompt delivery.
    RelayProperties settings =
        new RelayProperties(
            true, Duration.ofSeconds(30), 50, 8, Duration.ofSeconds(2), Duration.ofMinutes(5), Duration.ofMinutes(5));
    OutboxRelay relay =
        new OutboxRelay(database, inbox, mapper, () -> Instant.now().plusSeconds(1), metrics, settings, List.of());
    OutboxRelayWorker worker =
        new OutboxRelayWorker(relay, signal, settings, Instant::now, metrics, new DefaultApplicationArguments());
    worker.start();
    try {
      assertTrue(worker.isRunning());
      // Let the first pass find nothing and settle into its wait.
      Thread.sleep(300);
      publish("A");

      Instant deadline = Instant.now().plusSeconds(5);
      while (!"published".equals(status("A")) && Instant.now().isBefore(deadline)) {
        Thread.sleep(25);
      }
      assertEquals("published", status("A"));
    } finally {
      worker.stop();
    }
    assertFalse(worker.isRunning());
  }

  @Test
  void theWorkerStaysOffForACommandLineRun() {
    // `migrate` and its companions start the same application: the schema may
    // not exist yet, and the process is about to exit.
    RelayProperties settings =
        new RelayProperties(
            true, Duration.ofSeconds(30), 50, 8, Duration.ofSeconds(2), Duration.ofMinutes(5), Duration.ofMinutes(5));
    OutboxRelayWorker worker =
        new OutboxRelayWorker(
            relay(8), signal, settings, Instant::now, new Metrics(new SimpleMeterRegistry()),
            new DefaultApplicationArguments("migrate"));

    worker.start();

    assertFalse(worker.isRunning());
  }

  @Test
  void theWiredRelayRoutesEverySubscriberInTheApplication() {
    // Construction resolves each subscriber's event TYPE and refuses a duplicate
    // consumer name, so the context loading at all is most of the assertion.
    assertNotNull(wired);
    Set<String> names = new HashSet<>();
    for (EventSubscriber<?> subscriber : wiredSubscribers) {
      assertTrue(names.add(subscriber.consumerName()), subscriber.consumerName());
      assertTrue(
          subscriber.consumerName().startsWith(subscriber.moduleRole().roleName().replace("waypoint_", "") + "."),
          subscriber.consumerName() + " should be named after the module it runs as");
    }
    assertFalse(names.isEmpty());
  }

  // ---- helpers ----

  /** Records what it is given, for the test's own depots only. */
  private final class Recording implements EventSubscriber<OrdersClosed> {
    private final String name;
    final List<EventEnvelope<OrdersClosed>> seen = Collections.synchronizedList(new ArrayList<>());
    volatile Predicate<OrdersClosed> failing = event -> false;
    volatile int calls;

    Recording(String name) {
      this.name = "test.relay-" + name + "-" + tag;
    }

    @Override
    public String consumerName() {
      return name;
    }

    @Override
    public Class<OrdersClosed> eventType() {
      return OrdersClosed.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ORDERING;
    }

    @Override
    public void on(EventEnvelope<OrdersClosed> envelope) {
      if (!envelope.payload().depotCode().startsWith(tag)) {
        return;
      }
      calls++;
      if (failing.test(envelope.payload())) {
        throw new IllegalStateException("refused by the test");
      }
      seen.add(envelope);
    }

    List<String> depots() {
      synchronized (seen) {
        return seen.stream().map(e -> e.payload().depotCode()).toList();
      }
    }
  }

  private OutboxRelay relay(int maxAttempts, EventSubscriber<?>... subscribers) {
    return relay(maxAttempts, 50, subscribers);
  }

  private OutboxRelay relay(int maxAttempts, int batchSize, EventSubscriber<?>... subscribers) {
    RelayProperties settings =
        new RelayProperties(
            false,
            Duration.ofSeconds(1),
            batchSize,
            maxAttempts,
            Duration.ofSeconds(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(5));
    return new OutboxRelay(database, inbox, mapper, now::get, metrics, settings, List.of(subscribers));
  }

  private static void drain(OutboxRelay relay) {
    for (int pass = 0; pass < 200; pass++) {
      if (relay.deliverBatch() == 0) {
        return;
      }
    }
    throw new AssertionError("the relay never ran out of work");
  }

  private OrdersClosed closed(String depot, UUID... orders) {
    return new OrdersClosed(tag + depot, DAY, List.of(orders));
  }

  private void publish(String depot) {
    database.asSystem(ModuleRole.ORDERING, () -> publisher.publish(Actor.SYSTEM, closed(depot)));
  }

  private Map<String, Object> row(String depot) {
    return database.asSystemSeparately(
        ModuleRole.INTEGRATION,
        () ->
            database.queryOne(
                "SELECT * FROM integration.outbox_events WHERE aggregate_id = ? ORDER BY seq LIMIT 1",
                tag + depot + ":" + DAY));
  }

  private String status(String depot) {
    return (String) row(depot).get("status");
  }

  private int attempts(String depot) {
    return ((Number) row(depot).get("attempts")).intValue();
  }

  private String lastError(String depot) {
    return String.valueOf(row(depot).get("last_error"));
  }

  private UUID eventId(String depot) {
    return (UUID) row(depot).get("event_id");
  }

  private void abandon(String depot, Instant leaseUntil) {
    bookkeeping(
        "UPDATE integration.outbox_events SET status = 'processing', attempts = 1, next_attempt_at = ?"
            + " WHERE event_id = ?",
        Timestamp.from(leaseUntil),
        eventId(depot));
  }

  private void bookkeeping(String sql, Object... params) {
    database.asSystemSeparately(ModuleRole.INTEGRATION, () -> database.update(sql, params));
  }

  private long count(String sql, Object... params) {
    return ((Number)
            database
                .asSystemSeparately(ModuleRole.INTEGRATION, () -> database.queryOne(sql, params))
                .get("n"))
        .longValue();
  }

  private String account(String role) {
    String email = "relay-" + role + "-" + tag.toLowerCase() + "@waypoint.test";
    accounts.createAccount(email, "Relay " + role, PASSWORD, role);
    return email;
  }

  private static String replay(UUID eventId) {
    return """
        {"commandId":"%s","kind":"platform:ReplayEvent","expectedVersion":null,
         "payload":{"eventId":"%s"},"clientRecordedAt":"2026-10-01T08:00:00Z"}
        """
        .formatted(UUID.randomUUID(), eventId);
  }

  private String send(String email, String body, int expectedStatus) throws Exception {
    MvcResult result =
        http.perform(
                post("/api/commands")
                    .cookie(session(email))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expectedStatus, result.getResponse().getStatus(), response);
    return response;
  }

  private String read(String email, String path, int expectedStatus) throws Exception {
    MvcResult result = http.perform(get(path).cookie(session(email))).andReturn();
    String response = result.getResponse().getContentAsString();
    assertEquals(expectedStatus, result.getResponse().getStatus(), response);
    return response;
  }

  private Cookie session(String email) {
    return new Cookie(AuthController.COOKIE, login.login(email, PASSWORD, null, "127.0.0.1"));
  }
}
