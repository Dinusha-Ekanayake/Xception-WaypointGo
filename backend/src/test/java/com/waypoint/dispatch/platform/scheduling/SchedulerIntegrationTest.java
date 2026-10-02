package com.waypoint.dispatch.platform.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.platform.config.AuditProperties;
import com.waypoint.dispatch.platform.config.RetentionProperties;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.Migrator;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.support.TestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The scheduler's jobs against a real PostgreSQL (PLT-04, PLT-09, PLT-10): the
 * audit log gets its partitions ahead of time, old ones are detached, retention
 * removes only what is past its window, and a job runs on one instance at a time.
 *
 * <p>Time is a parameter throughout, so a year passes in milliseconds.
 */
@SpringBootTest
@ExtendWith(TestDatabase.class)
class SchedulerIntegrationTest {

  @Autowired Migrator migrator;
  @Autowired Database database;
  @Autowired DataSource dataSource;
  @Autowired Metrics metrics;
  @Autowired MeterRegistry meters;
  /**
   * Retention off. These tests run the job with a clock years ahead, and with the
   * real 24 month window that would detach the partitions every other test writes to.
   */
  private AuditPartitionJob partitionJob;

  @Autowired PlatformRetentionJob retentionJob;
  @Autowired RetentionProperties retention;

  @DynamicPropertySource
  static void databaseUrl(DynamicPropertyRegistry registry) {
    registry.add("app.database-url", TestDatabase::url);
    registry.add("app.scheduling.enabled", () -> "false");
  }

  @BeforeEach
  void migrate() {
    migrator.migrate();
    partitionJob = new AuditPartitionJob(database, new AuditProperties(3, 0), metrics);
  }

  @Test
  void theJobCreatesTheMonthsAheadAndTheSecondRunChangesNothing() {
    Instant farFuture = Instant.parse("2031-03-10T09:00:00Z");

    partitionJob.run(farFuture);
    List<String> afterFirst = partitionJob.partitions();
    partitionJob.run(farFuture);

    for (String month : List.of("2031_03", "2031_04", "2031_05", "2031_06")) {
      assertTrue(afterFirst.contains("audit_log_" + month), month + " must exist: " + afterFirst);
    }
    assertEquals(afterFirst.size(), partitionJob.partitions().size(), "running twice is a no-op");
  }

  @Test
  void aRowFallingInACreatedPartitionIsAccepted() {
    Instant farFuture = Instant.parse("2032-01-10T09:00:00Z");
    partitionJob.run(farFuture);

    // The reason the job exists: without a partition this insert fails, and so does
    // every command that audits in the same transaction.
    database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.update(
                "INSERT INTO integration.audit_log (occurred_at, action, decision)"
                    + " VALUES (?, 'test:Partition', 'ALLOW')",
                Timestamp.from(Instant.parse("2032-03-20T00:00:00Z"))));
  }

  @Test
  void aNewPartitionIsAppendOnlyForTheIntegrationRole() {
    partitionJob.run(Instant.parse("2033-01-10T09:00:00Z"));

    boolean mayUpdate =
        (Boolean)
            database
                .unscopedQuery(
                    "SELECT has_table_privilege('waypoint_integration',"
                        + " 'integration.audit_log_2033_02', 'UPDATE') AS ok")
                .get(0)
                .get("ok");
    boolean mayDelete =
        (Boolean)
            database
                .unscopedQuery(
                    "SELECT has_table_privilege('waypoint_integration',"
                        + " 'integration.audit_log_2033_02', 'DELETE') AS ok")
                .get(0)
                .get("ok");

    assertFalse(mayUpdate, "audit is append only on a partition created later too");
    assertFalse(mayDelete);
  }

  @Test
  void detachingKeepsTheDataAndTheNameIsValidated() {
    // A month that exists for this test alone. Dropped first so a rerun starts clean.
    database.unscopedQuery("DROP TABLE IF EXISTS integration.audit_log_2031_01");
    database.asSystem(
        ModuleRole.INTEGRATION,
        () -> database.queryOne("SELECT integration.ensure_audit_partition('2031-01-01'::date)"));
    database.asSystem(
        ModuleRole.INTEGRATION,
        () ->
            database.update(
                "INSERT INTO integration.audit_log (occurred_at, action, decision)"
                    + " VALUES (?, 'test:Old', 'ALLOW')",
                Timestamp.from(Instant.parse("2031-01-05T00:00:00Z"))));

    boolean detached =
        (Boolean)
            database
                .asSystem(
                    ModuleRole.INTEGRATION,
                    () ->
                        database.queryOne(
                            "SELECT integration.detach_audit_partition('audit_log_2031_01') AS done"))
                .get("done");

    assertTrue(detached);
    assertFalse(partitionJob.partitions().contains("audit_log_2031_01"), "detached from the log");
    long kept =
        ((Number)
                database
                    .unscopedQuery("SELECT count(*) AS n FROM integration.audit_log_2031_01")
                    .get(0)
                    .get("n"))
            .longValue();
    assertEquals(1, kept, "detaching is not deleting");
    assertThrows(
        RuntimeException.class,
        () ->
            database.asSystem(
                ModuleRole.INTEGRATION,
                () ->
                    database.queryOne(
                        "SELECT integration.detach_audit_partition('audit_log; DROP TABLE x')")),
        "only an audit partition name is accepted");
  }

  @Test
  void retentionRemovesOnlyRowsPastTheirWindowAndNeverADeadEvent() {
    Instant now = Instant.parse("2035-01-01T00:00:00Z");
    Instant old = now.minus(Duration.ofDays(400));
    UUID oldCommand = UUID.randomUUID();
    UUID freshCommand = UUID.randomUUID();
    UUID oldPublished = UUID.randomUUID();
    UUID freshPublished = UUID.randomUUID();
    UUID oldDead = UUID.randomUUID();

    database.asSystem(
        ModuleRole.INTEGRATION,
        () -> {
          receipt(oldCommand, old);
          receipt(freshCommand, now.minus(Duration.ofDays(1)));
          outbox(oldPublished, "published", old);
          outbox(freshPublished, "published", now.minus(Duration.ofDays(1)));
          outbox(oldDead, "dead", old);
        });

    retentionJob.run(now);

    assertEquals(0, rows("command_receipts", "command_id", oldCommand));
    assertEquals(1, rows("command_receipts", "command_id", freshCommand));
    assertEquals(0, rows("outbox_events", "event_id", oldPublished));
    assertEquals(1, rows("outbox_events", "event_id", freshPublished));
    assertEquals(1, rows("outbox_events", "event_id", oldDead), "a dead event waits for an administrator");
  }

  @Test
  void twoRunnersOnOneJobRunItOnceAndCountTheDuplicate() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    int[] runs = {0};
    ScheduledJob slow =
        new ScheduledJob() {
          public String name() {
            return "test.duplicate-" + UUID.randomUUID();
          }

          public String cron() {
            return "0 0 0 1 1 *";
          }

          public ModuleRole moduleRole() {
            return ModuleRole.INTEGRATION;
          }

          public void run(Instant now) {
            runs[0]++;
            inside.countDown();
            try {
              release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    var runnerA =
        new ScheduledJobRunner(
            List.of(slow), dataSource, database, com.waypoint.dispatch.shared.util.Clock.system(), metrics);
    var runnerB =
        new ScheduledJobRunner(
            List.of(slow), dataSource, database, com.waypoint.dispatch.shared.util.Clock.system(), metrics);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first = pool.submit(() -> runnerA.runOnce(slow));
      assertTrue(inside.await(20, TimeUnit.SECONDS), "the first runner took the lease");

      boolean second = runnerB.runOnce(slow);
      release.countDown();

      assertFalse(second, "the second instance must not run the job");
      assertTrue(first.get(20, TimeUnit.SECONDS));
      assertEquals(1, runs[0]);
      assertEquals(1.0, meters.get("waypoint.job.duplicate").tag("job", slow.name()).counter().count());
      Map<String, Object> recorded =
          database.asSystem(
              ModuleRole.INTEGRATION,
              () ->
                  database.queryOne(
                      "SELECT count(*) AS n, min(outcome) AS outcome FROM integration.job_runs"
                          + " WHERE job = ?",
                      slow.name()));
      assertEquals(1L, ((Number) recorded.get("n")).longValue(), "one run is recorded, not two");
      assertEquals("ok", recorded.get("outcome"));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  // ---- helpers ----

  private void receipt(UUID commandId, Instant at) {
    database.update(
        "INSERT INTO integration.command_receipts"
            + " (command_id, actor_id, command_kind, payload_hash, result_status, created_at)"
            + " VALUES (?, ?, 'test', 'h', 200, ?)",
        commandId,
        UUID.randomUUID(),
        Timestamp.from(at));
  }

  private void outbox(UUID eventId, String status, Instant at) {
    database.update(
        "INSERT INTO integration.outbox_events (event_id, aggregate_type, aggregate_id, event_type,"
            + " payload, occurred_at, status, dead_lettered_at)"
            + " VALUES (?, 'test', 'x', 'test.retention', '{}'::jsonb, ?, ?, ?)",
        eventId,
        Timestamp.from(at),
        status,
        "dead".equals(status) ? Timestamp.from(at) : null);
  }

  private long rows(String table, String column, UUID id) {
    return ((Number)
            database.asSystem(
                    ModuleRole.INTEGRATION,
                    () ->
                        database.queryOne(
                            "SELECT count(*) AS n FROM integration." + table + " WHERE " + column + " = ?",
                            id))
                .get("n"))
        .longValue();
  }
}
