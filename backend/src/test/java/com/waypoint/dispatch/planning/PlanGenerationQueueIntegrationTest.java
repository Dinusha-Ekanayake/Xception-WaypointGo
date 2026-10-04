package com.waypoint.dispatch.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.waypoint.dispatch.planning.application.GeneratePlanHandler;
import com.waypoint.dispatch.planning.application.GeneratePlanHandler.JobInput;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.infrastructure.JdbcGenerationJobs;
import com.waypoint.dispatch.planning.infrastructure.JdbcGenerationJobs.JobRow;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.shared.domain.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Plan generation as a queued job (planning v2, R-PLN-41): one job per depot
 * and day however often Generate is pressed, the engine outside any
 * transaction, a rerun when the orders change under it, a lease that lets
 * another worker take over, and scope on reading a job.
 */
class PlanGenerationQueueIntegrationTest extends PlanningIntegrationSupport {
  @Autowired GeneratePlanHandler generator;
  @Autowired JdbcGenerationJobs jobs;

  JsonNode queue() throws Exception {
    return mapper.readTree(send(dispatcher, envelope("plan:Generate", null, generatePayload()), 200)).get("result");
  }

  /** Jobs for this test's day only: other tests share the database and its queue. */
  long jobsForTheDay() {
    return ((Number) database.asSystem(ModuleRole.PLANNING, () -> database.queryOne(
        "SELECT count(*) AS n FROM planning.generation_jobs WHERE depot_code = ? AND service_date = ?",
        depot, java.sql.Date.valueOf(serviceDate)).get("n"))).longValue();
  }

  @Test
  void pressingGenerateTwiceQueuesOneJobAndItWritesOneDraft() throws Exception {
    demand("ambient");
    demand("ambient");
    JsonNode first = queue();
    JsonNode second = queue();
    assertEquals("QUEUED", first.get("status").asText());
    assertEquals(first.get("jobId").asText(), second.get("jobId").asText(), "the second Generate finds the first job");

    worker.runPending();
    assertEquals(1, jobsForTheDay(), "one job written for the day");
    JsonNode job = mapper.readTree(read(dispatcher, "/api/plans/jobs/" + first.get("jobId").asText() + "?depot=" + depot, 200));
    assertEquals("DONE", job.get("status").asText());
    assertEquals(2, job.get("result").get("served").asInt());
    JsonNode draft = mapper.readTree(read(dispatcher, "/api/plans/draft?depot=" + depot + "&date=" + serviceDate, 200));
    assertEquals(job.get("planId").asText(), draft.get("planId").asText());
    JsonNode latest = mapper.readTree(read(dispatcher, "/api/plans/jobs?depot=" + depot + "&date=" + serviceDate, 200));
    assertEquals(first.get("jobId").asText(), latest.get("jobId").asText(), "a screen opened later finds the job");
  }

  @Test
  void twoDispatchersPressingAtOnceGetTheSameJob() throws Exception {
    demand("ambient");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<String> press = () -> queue().get("jobId").asText();
      List<Future<String>> pressed = pool.invokeAll(List.of(press, press));
      Set<String> ids = pressed.stream().map(f -> {
        try {
          return f.get();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }).collect(Collectors.toSet());
      assertEquals(1, ids.size(), "one active job per depot and day: " + ids);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, jobsForTheDay());
  }

  @Test
  void ordersThatChangeWhileTheEngineRunsAreNotWrittenAsAStalePlan() throws Exception {
    demand("ambient");
    UUID me = userId(dispatcherEmail);
    JobInput input = database.asModule(ModuleRole.PLANNING, me, () -> generator.prepareJob(depot, serviceDate, false));
    AllocationResult result = generator.allocate(input);

    demand("ambient"); // an order arrives while the engine runs

    Optional<PlanningRun> written = database.asModule(ModuleRole.PLANNING, me,
        () -> generator.persistJob(Actor.user(me), input, result, Instant.now(), UUID.randomUUID()));
    assertTrue(written.isEmpty(), "the job runs again on the new demand rather than write a plan for the old one");
    read(dispatcher, "/api/plans/draft?depot=" + depot + "&date=" + serviceDate, 404);
  }

  @Test
  void aJobWhoseWorkerDiedIsClaimedAgainWhenItsLeaseLapses() throws Exception {
    demand("ambient");
    UUID jobId = UUID.fromString(queue().get("jobId").asText());
    Instant now = Instant.now();
    Optional<JobRow> taken = database.asSystem(ModuleRole.PLANNING, () -> jobs.claim(now, Duration.ofMinutes(2)));
    assertEquals(jobId, taken.orElseThrow().jobId());
    // The worker dies holding it: nobody else may take it inside the lease...
    assertTrue(database.asSystem(ModuleRole.PLANNING, () -> jobs.claim(now.plusSeconds(60), Duration.ofMinutes(2))).isEmpty());
    // ...and after it lapses, another worker does.
    JobRow again = database.asSystem(ModuleRole.PLANNING, () -> jobs.claim(now.plusSeconds(180), Duration.ofMinutes(2)))
        .orElseThrow();
    assertEquals(jobId, again.jobId());
    assertEquals(2, again.attempts());
  }

  @Test
  void anotherDepotsDispatcherCannotReadTheJob() throws Exception {
    demand("ambient");
    String jobId = queue().get("jobId").asText();
    read(elsewhere, "/api/plans/jobs/" + jobId + "?depot=" + depot, 403);
    read(elsewhere, "/api/plans/jobs?depot=" + depot + "&date=" + serviceDate, 403);
  }
}
