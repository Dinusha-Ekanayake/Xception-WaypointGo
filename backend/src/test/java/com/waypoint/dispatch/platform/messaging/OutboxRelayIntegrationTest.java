package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrdersClosed;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.messaging.DeadLetterQuery.DeadLetterView;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.event.DomainEvent;
import com.waypoint.dispatch.shared.event.EventEnvelope;
import com.waypoint.dispatch.support.TestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The relay against a real PostgreSQL (PLT-02, PLT-03): what is delivered, to
 * whom, how often, in what order, and what happens when a subscriber fails.
 *
 * <p>Events are written straight into the outbox under a test-only type with two
 * test subscribers, so no real consumer runs on a fabricated payload. The relay is
 * driven by hand with an explicit {@code now}, which is how a backoff of minutes
 * passes in milliseconds.
 */
@SpringBootTest
@ExtendWith(TestDatabase.class)
@Import(OutboxRelayIntegrationTest.Probes.class)
class OutboxRelayIntegrationTest {

  /** The test-only event. Not in a contract package, so it is never published, only inserted. */
  public record Probe(String note, String aggregate) implements DomainEvent {
    public static final String TYPE = "test.relay_probe";

    @Override
    public String type() {
      return TYPE;
    }

    @Override
    public String aggregateType() {
      return "probe";
    }

    @Override
    public String aggregateId() {
      return aggregate;
    }
  }

  /** Records every delivery, and can be told to fail for one note. */
  static final class Recorder implements EventSubscriber<Probe> {
    private final String name;
    final List<UUID> seen = Collections.synchronizedList(new ArrayList<>());
    final List<String> notes = Collections.synchronizedList(new ArrayList<>());
    volatile String failOnNote;

    Recorder(String name) {
      this.name = name;
    }

    @Override
    public String consumerName() {
      return name;
    }

    @Override
    public Class<Probe> eventType() {
      return Probe.class;
    }

    @Override
    public ModuleRole moduleRole() {
      return ModuleRole.ORDERING;
    }

    @Override
    public void on(EventEnvelope<Probe> envelope) {
      if (envelope.payload().note().equals(failOnNote)) {
        throw new IllegalStateException("subscriber " + name + " refuses " + failOnNote);
      }
      seen.add(envelope.eventId());
      notes.add(envelope.payload().note());
    }

    void reset() {
      seen.clear();
      notes.clear();
      failOnNote = null;
    }
  }

  @TestConfiguration
  static class Probes {
    @Bean
    Recorder probeA() {
      return new Recorder("test.probe-a");
    }

    @Bean
    Recorder probeB() {
      return new Recorder("test.probe-b");
    }
  }

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired OutboxRelay relay;
  @Autowired ObjectMapper mapper;
  @Autowired EventPublisher publisher;
  @Autowired MeterRegistry meters;
  @Autowired DeadLetterQuery deadLetters;
  @Autowired ReplayEventHandler replay;
  @Autowired Recorder probeA;
  @Autowired Recorder probeB;

  private final Instant t0 = Instant.now();
  private final AtomicInteger sequence = new AtomicInteger();

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
  }

  @BeforeEach
  void quietTheOutbox() {
    migrator.migrate();
    probeA.reset();
    probeB.reset();
    // Rows another test class left behind would be claimed ahead of ours.
    database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.update(
                "UPDATE integration.outbox_events SET status = 'published'"
                    + " WHERE status IN ('pending', 'failed', 'processing')"));
  }

  @Test
  void anEventIsDeliveredToEverySubscriberOnceAndThenPublished() {
    UUID event = insert("hello", "agg-" + UUID.randomUUID());

    assertEquals(1, relay.runOnce(t0));

    assertEquals(List.of(event), probeA.seen);
    assertEquals(List.of(event), probeB.seen);
    assertEquals("published", statusOf(event));
    assertEquals(0, relay.runOnce(t0.plusSeconds(1)), "a published event is not delivered again");
  }

  @Test
  void aFailingSubscriberIsRetriedAloneAndTheOneThatSucceededIsNotRunAgain() {
    probeB.failOnNote = "flaky";
    UUID event = insert("flaky", "agg-" + UUID.randomUUID());

    relay.runOnce(t0);

    assertEquals("failed", statusOf(event));
    assertEquals(1, attemptsOf(event));
    assertEquals(List.of(event), probeA.seen);
    assertTrue(probeB.seen.isEmpty());

    probeB.failOnNote = null;
    relay.runOnce(t0.plus(Duration.ofHours(1)));

    assertEquals("published", statusOf(event));
    assertEquals(List.of(event), probeA.seen, "the inbox stopped the first subscriber re-applying it");
    assertEquals(List.of(event), probeB.seen);
  }

  @Test
  void aRetryWaitsForItsBackoff() {
    probeB.failOnNote = "flaky";
    UUID event = insert("flaky", "agg-" + UUID.randomUUID());
    relay.runOnce(t0);
    probeB.failOnNote = null;

    assertEquals(0, relay.runOnce(t0), "the retry is not due yet");
    assertEquals("failed", statusOf(event));
  }

  @Test
  void aPoisonEventIsDeadLetteredWithItsHistoryAndNeverBlocksOtherAggregates() {
    probeB.failOnNote = "poison";
    UUID poison = insert("poison", "agg-poison-" + UUID.randomUUID());
    UUID healthy = insert("fine", "agg-healthy-" + UUID.randomUUID());

    for (int pass = 0; pass < 8; pass++) {
      relay.runOnce(t0.plus(Duration.ofHours(pass + 1)));
    }

    assertEquals("dead", statusOf(poison));
    assertEquals(8, attemptsOf(poison));
    assertEquals("published", statusOf(healthy), "a poison event must not stop the queue");

    List<Map<String, Object>> history = history(poison);
    assertEquals(8, history.size(), "every failed delivery is kept");
    assertEquals("dead", history.get(7).get("outcome"));
    assertTrue(
        String.valueOf(history.get(0).get("error")).contains("test.probe-b"),
        "the history names the subscriber that failed");
    assertEquals(1, deadCount(poison), "it is visible to an administrator");
    assertEquals(0, relay.runOnce(t0.plus(Duration.ofDays(1))), "a dead event is not retried by itself");
  }

  @Test
  void anEventWaitsForTheEarlierEventOfItsAggregate() {
    probeB.failOnNote = "first";
    String aggregate = "agg-" + UUID.randomUUID();
    UUID first = insert("first", aggregate);
    UUID second = insert("second", aggregate);

    relay.runOnce(t0);

    assertEquals("failed", statusOf(first));
    assertEquals("pending", statusOf(second), "the second must not overtake the first");
    assertFalse(probeB.notes.contains("second"));

    probeB.failOnNote = null;
    relay.runOnce(t0.plus(Duration.ofHours(1)));
    relay.runOnce(t0.plus(Duration.ofHours(2)));

    assertEquals(List.of("first", "second"), probeB.notes, "delivered in the order written");
  }

  @Test
  void aDeadEventDoesNotHoldBackItsSuccessorsAndAReplayDeliversItLater() {
    probeB.failOnNote = "poison";
    String aggregate = "agg-" + UUID.randomUUID();
    UUID poison = insert("poison", aggregate);
    UUID after = insert("after", aggregate);
    for (int pass = 0; pass < 9; pass++) {
      relay.runOnce(t0.plus(Duration.ofHours(pass + 1)));
    }
    assertEquals("dead", statusOf(poison));
    assertEquals("published", statusOf(after), "the successor goes once the poison event is dead");

    probeB.failOnNote = null;
    UUID admin = UUID.randomUUID();
    Object result =
        database.asModule(
            ModuleRole.INTEGRATION,
            admin,
            () -> replay.handle(Actor.user(admin), command(poison, "cause fixed")));

    assertEquals(Map.of("eventId", poison.toString(), "status", "pending"), result);
    assertEquals("pending", statusOf(poison));
    assertEquals(0, attemptsOf(poison), "a replay restarts the attempts");
    assertEquals("replayed", history(poison).get(history(poison).size() - 1).get("outcome"));
    assertEquals(admin, history(poison).get(history(poison).size() - 1).get("actor_id"));

    relay.runOnce(t0.plus(Duration.ofDays(2)));

    assertEquals("published", statusOf(poison));
    assertEquals(List.of("after", "poison"), probeB.notes);
  }

  @Test
  void aReplayNeedsAReasonAndADeadEvent() {
    UUID admin = UUID.randomUUID();
    UUID live = insert("live", "agg-" + UUID.randomUUID());

    assertDomain(
        "reason",
        () ->
            database.asModule(
                ModuleRole.INTEGRATION,
                admin,
                () -> replay.handle(Actor.user(admin), command(live, " "))));
    assertDomain(
        "not dead-lettered",
        () ->
            database.asModule(
                ModuleRole.INTEGRATION,
                admin,
                () -> replay.handle(Actor.user(admin), command(live, "try it"))));
    assertEquals("pending", statusOf(live), "a live event is left as it was");
  }

  @Test
  void deadLettersAreListedNewestFirstWithoutThePayloadAndReadableOneByOne() {
    probeB.failOnNote = "poison";
    UUID poison = insert("poison", "agg-" + UUID.randomUUID());
    for (int pass = 0; pass < 8; pass++) {
      relay.runOnce(t0.plus(Duration.ofHours(pass + 1)));
    }
    Actor admin = Actor.user(UUID.randomUUID());

    var page = deadLetters.list(admin, Optional.empty(), 200);
    DeadLetterView listed =
        page.items().stream().filter(v -> v.eventId().equals(poison)).findFirst().orElseThrow();
    DeadLetterView detail = deadLetters.get(admin, poison);

    assertEquals("test.relay_probe", listed.eventType());
    assertEquals(8, detail.attempts());
    assertEquals(8, detail.history().size());
    assertTrue(listed.history().isEmpty(), "history is on the detail, not the list");
  }

  @Test
  void twoRelaysDoNotDeliverTheSameEventTwice() throws Exception {
    List<UUID> events = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      events.add(insert("n" + i, "agg-" + UUID.randomUUID()));
    }
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> runs = new ArrayList<>();
      for (int relayInstance = 0; relayInstance < 2; relayInstance++) {
        runs.add(
            pool.submit(
                () -> {
                  int handled = 0;
                  int pass;
                  while ((pass = relay.runOnce(t0)) > 0) {
                    handled += pass;
                  }
                  return handled;
                }));
      }
      int total = 0;
      for (Future<Integer> run : runs) {
        total += run.get();
      }

      assertEquals(60, total, "every event was handled by exactly one relay");
    } finally {
      pool.shutdownNow();
    }
    assertEquals(60, probeA.seen.size());
    assertEquals(60, probeA.seen.stream().distinct().count(), "no event reached a subscriber twice");
    for (UUID event : events) {
      assertEquals("published", statusOf(event));
    }
  }

  @Test
  void aRelayThatDiedMidBatchIsRecoveredWhenItsLeaseExpires() {
    UUID event = insert("orphan", "agg-" + UUID.randomUUID());
    database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.update(
                "UPDATE integration.outbox_events SET status = 'processing', locked_until = ?"
                    + " WHERE event_id = ?",
                Timestamp.from(t0.plusSeconds(60)),
                event));

    assertEquals(0, relay.runOnce(t0), "the lease is still held");
    assertEquals(1, relay.runOnce(t0.plusSeconds(61)), "an expired lease is claimed again");
    assertEquals("published", statusOf(event));
  }

  @Test
  void anEventFromARolledBackTransactionIsNeverDelivered() {
    String depot = "RB" + UUID.randomUUID().toString().substring(0, 6);
    try {
      database.asSystem(
          ModuleRole.ORDERING,
          () -> {
            publisher.publish(
                Actor.SYSTEM, new OrdersClosed(depot, LocalDate.parse("2027-03-01"), List.of()));
            throw new IllegalStateException("the change failed after publishing");
          });
    } catch (IllegalStateException expected) {
      // the rollback is the point
    }

    assertEquals(0, relay.runOnce(t0), "nothing was committed, so nothing is there to deliver");
  }

  @Test
  void theLagAndDeadGaugesReportWhatAnOperatorAlertsOn() {
    UUID stuck = insert("stuck", "agg-" + UUID.randomUUID());
    database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.update(
                "UPDATE integration.outbox_events SET occurred_at = ?, next_attempt_at = ?"
                    + " WHERE event_id = ?",
                Timestamp.from(t0.minusSeconds(600)),
                Timestamp.from(t0.plusSeconds(3600)),
                stuck));

    relay.refreshGauges(t0);

    double lag = meters.get("waypoint.outbox.lag_seconds").gauge().value();
    assertTrue(lag >= 600, "the oldest undelivered event is ten minutes old: " + lag);
    assertTrue(meters.get("waypoint.outbox.dead").gauge().value() >= 0);
  }

  // ---- helpers ----

  /** Writes a due event straight into the outbox, as the publisher would have. */
  private UUID insert(String note, String aggregate) {
    UUID id = UUID.randomUUID();
    Timestamp due = Timestamp.from(t0.minusSeconds(60));
    String payload;
    try {
      payload = mapper.writeValueAsString(new Probe(note, aggregate));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.update(
                """
                INSERT INTO integration.outbox_events
                    (event_id, aggregate_type, aggregate_id, event_type, event_version, payload,
                     occurred_at, next_attempt_at, producer)
                VALUES (?, 'probe', ?, 'test.relay_probe', 1, ?::jsonb, ?, ?, 'test')
                """,
                id,
                aggregate,
                payload,
                // Strictly increasing, so "written first" has a meaning inside one test.
                Timestamp.from(due.toInstant().plusMillis(sequence.incrementAndGet())),
                due));
    return id;
  }

  private Command command(UUID eventId, String reason) {
    return new Command(
        UUID.randomUUID(),
        ReplayEventHandler.KIND,
        null,
        mapper.createObjectNode().put("eventId", eventId.toString()).put("reason", reason),
        Instant.now());
  }

  private String statusOf(UUID event) {
    return String.valueOf(row(event).get("status"));
  }

  private int attemptsOf(UUID event) {
    return ((Number) row(event).get("attempts")).intValue();
  }

  private Map<String, Object> row(UUID event) {
    return database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.queryOne(
                "SELECT status, attempts FROM integration.outbox_events WHERE event_id = ?", event));
  }

  private List<Map<String, Object>> history(UUID event) {
    return database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.query(
                "SELECT outcome, error, actor_id FROM integration.outbox_attempts"
                    + " WHERE event_id = ? ORDER BY attempt_id",
                event));
  }

  private long deadCount(UUID event) {
    return ((Number)
            database.asSystem(
                    ModuleRole.INTEGRATION,
                    () ->
                        database.queryOne(
                            "SELECT count(*) AS n FROM integration.outbox_events"
                                + " WHERE event_id = ? AND status = 'dead'",
                            event))
                .get("n"))
        .longValue();
  }

  private static void assertDomain(String messagePart, Runnable action) {
    AtomicBoolean thrown = new AtomicBoolean();
    try {
      action.run();
    } catch (DomainException e) {
      thrown.set(true);
      assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }
    assertTrue(thrown.get(), "expected a DomainException mentioning " + messagePart);
  }
}
