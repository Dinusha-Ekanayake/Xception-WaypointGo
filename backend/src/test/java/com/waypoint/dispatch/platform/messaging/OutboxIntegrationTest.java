package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.ordering.contract.OrderEvents.OrdersClosed;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The write half of the event backbone against a real PostgreSQL: an event
 * commits with the change that caused it or not at all.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(
    named = "TEST_DATABASE_URL",
    matches = ".+",
    disabledReason = "Set TEST_DATABASE_URL to a dedicated database to run integration tests")
class OutboxIntegrationTest {

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired EventPublisher publisher;
  @Autowired ConsumerInbox inbox;

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
  void migrate() {
    migrator.migrate();
  }

  @Test
  void anEventCommitsWithTheTransactionThatPublishedIt() throws Exception {
    String depot = "T" + UUID.randomUUID().toString().substring(0, 8);
    Actor actor = Actor.user(UUID.randomUUID());

    database.asModule(
        ModuleRole.ORDERING,
        actor.userId(),
        () -> publisher.publish(actor, closed(depot)));

    List<Map<String, Object>> rows = outboxFor(depot);
    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals("orders.closed", row.get("event_type"));
    assertEquals("ordering", row.get("producer"));
    assertEquals(1, row.get("event_version"));
    assertEquals(actor.userId(), row.get("actor_id"));
    assertEquals("pending", row.get("status"));
    JsonNode payload = new ObjectMapper().readTree(String.valueOf(row.get("payload")));
    assertEquals("2027-03-01", payload.get("serviceDate").asText(), "dates travel as ISO text");
    assertEquals(depot, payload.get("depotCode").asText());
  }

  @Test
  void anEventFromARolledBackTransactionIsNeverWritten() {
    String depot = "T" + UUID.randomUUID().toString().substring(0, 8);

    assertThrows(
        IllegalStateException.class,
        () ->
            database.asSystem(
                ModuleRole.ORDERING,
                () -> {
                  publisher.publish(Actor.SYSTEM, closed(depot));
                  throw new IllegalStateException("the change failed after publishing");
                }));

    assertTrue(outboxFor(depot).isEmpty());
  }

  @Test
  void publishingOutsideATransactionIsRefused() {
    assertThrows(IllegalStateException.class, () -> publisher.publish(Actor.SYSTEM, closed("X")));
  }

  @Test
  void theSystemActorIsRecordedAsNobodyAndRecognisedInSql() {
    String depot = "T" + UUID.randomUUID().toString().substring(0, 8);

    Object isSystem =
        database.asSystem(
            ModuleRole.ORDERING,
            () -> {
              publisher.publish(Actor.SYSTEM, closed(depot));
              return database.queryOne("SELECT app.actor_is_system() AS s").get("s");
            });
    Object isUser =
        database.asModule(
            ModuleRole.ORDERING,
            UUID.randomUUID(),
            () -> database.queryOne("SELECT app.actor_is_system() AS s").get("s"));

    assertEquals(Boolean.TRUE, isSystem);
    assertEquals(Boolean.FALSE, isUser);
    assertNull(outboxFor(depot).get(0).get("actor_id"));
  }

  @Test
  void aConsumerClaimsAnEventOnce() {
    UUID eventId = UUID.randomUUID();

    boolean first = database.asSystem(ModuleRole.ORDERING, () -> inbox.claim("test.consumer", eventId));
    boolean second =
        database.asSystem(ModuleRole.ORDERING, () -> inbox.claim("test.consumer", eventId));
    boolean otherConsumer =
        database.asSystem(ModuleRole.ORDERING, () -> inbox.claim("test.other", eventId));

    assertTrue(first);
    assertFalse(second, "a redelivery must be a no-op");
    assertTrue(otherConsumer, "each consumer applies an event once, independently");
  }

  private static OrdersClosed closed(String depot) {
    return new OrdersClosed(depot, LocalDate.parse("2027-03-01"), List.of());
  }

  private List<Map<String, Object>> outboxFor(String depot) {
    return database.asModule(
        ModuleRole.INTEGRATION,
        null,
        () ->
            database.query(
                "SELECT * FROM integration.outbox_events WHERE aggregate_id = ?",
                depot + ":2027-03-01"));
  }
}
