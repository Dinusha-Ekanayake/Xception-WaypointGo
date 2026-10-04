package com.waypoint.dispatch.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.planning.application.GeneratePlanHandler.JobInput;
import com.waypoint.dispatch.planning.domain.AllocationEngine.AllocationResult;
import com.waypoint.dispatch.planning.domain.PlanningRun;
import com.waypoint.dispatch.planning.infrastructure.JdbcGenerationJobs;
import com.waypoint.dispatch.planning.infrastructure.JdbcGenerationJobs.JobRow;
import com.waypoint.dispatch.platform.db.Database;
import com.waypoint.dispatch.platform.db.ModuleRole;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.domain.Actor;
import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs queued plan generations (planning v2, R-PLN-41).
 *
 * <p>Each job is three steps: read the day in a short transaction, run the
 * engine with no transaction open, write the draft in another short
 * transaction. The write first checks the demand is what the engine saw; if an
 * order changed meanwhile, the job runs again on the new demand, up to
 * {@link #MAX_ATTEMPTS} times, then fails with the reason (rule 8). A refusal
 * from the rules (not an operating day, already published) fails it at once.
 *
 * <p>{@code PLANNING_WORKERS} threads (default 2) share the queue, and so do
 * other replicas: a job is claimed with {@code SKIP LOCKED} and a lease, and a
 * worker that dies lets its lease lapse so another claims the job. Different
 * depots and days plan in parallel; one day never plans twice at once.
 *
 * <p>Off with {@code app.planning.worker.enabled=false}, which tests use so
 * that a job runs only when a test calls {@link #runPending()}.
 */
@Component
public class PlanGenerationWorker implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(PlanGenerationWorker.class);
  static final int MAX_ATTEMPTS = 3;
  static final Duration LEASE = Duration.ofMinutes(2);
  private static final long POLL_MS = 2_000;

  private final GeneratePlanHandler generator;
  private final JdbcGenerationJobs jobs;
  private final Database database;
  private final GenerationSignal signal;
  private final Metrics metrics;
  private final Clock clock;
  private final ObjectMapper json;
  private final boolean enabled;
  private final int threads;
  private final boolean serving;
  private final AtomicLong waiting = new AtomicLong();
  private final List<Thread> workers = new ArrayList<>();
  private volatile boolean running;

  public PlanGenerationWorker(
      GeneratePlanHandler generator,
      JdbcGenerationJobs jobs,
      Database database,
      GenerationSignal signal,
      Metrics metrics,
      Clock clock,
      ObjectMapper json,
      ApplicationArguments arguments,
      @Value("${app.planning.worker.enabled:true}") boolean enabled,
      @Value("${PLANNING_WORKERS:2}") int threads) {
    this.generator = generator;
    this.jobs = jobs;
    this.database = database;
    this.signal = signal;
    this.metrics = metrics;
    this.clock = clock;
    this.json = json;
    this.enabled = enabled;
    this.threads = Math.max(1, threads);
    this.serving = arguments.getNonOptionArgs().isEmpty();
    metrics.gauge("waypoint.planning.jobs.waiting", waiting::get);
  }

  /** Runs every job that can be claimed now, one after another; how many finished. For tests and the loop. */
  public int runPending() {
    int finished = 0;
    while (true) {
      Optional<JobRow> claimed = database.asSystem(ModuleRole.PLANNING, () -> jobs.claim(clock.now(), LEASE));
      if (claimed.isEmpty()) {
        return finished;
      }
      run(claimed.get());
      finished++;
    }
  }

  private void run(JobRow job) {
    long started = System.nanoTime();
    Actor actor = job.requestedBy().equals(Actor.SYSTEM_ID) ? Actor.SYSTEM : Actor.user(job.requestedBy());
    try {
      JobInput input =
          database.asModule(ModuleRole.PLANNING, actor.userId(),
              () -> generator.prepareJob(job.depotCode(), job.serviceDate(), job.keepDecisions()));
      metrics.record("waypoint.planning.job.duration", ms(started), "stage", "prepare");
      long engineStart = System.nanoTime();
      AllocationResult result = generator.allocate(input);
      metrics.record("waypoint.planning.job.duration", ms(engineStart), "stage", "engine");
      long writeStart = System.nanoTime();
      Instant now = clock.now();
      Optional<PlanningRun> run =
          database.asModule(ModuleRole.PLANNING, actor.userId(), () -> {
            Optional<PlanningRun> written = generator.persistJob(actor, input, result, now, job.commandId());
            written.ifPresent(r -> jobs.done(job.jobId(), r.planId(), body(r), now));
            return written;
          });
      metrics.record("waypoint.planning.job.duration", ms(writeStart), "stage", "write");
      if (run.isPresent()) {
        metrics.increment("waypoint.planning.job.done");
        return;
      }
      settle(job, "the orders for the day changed while the plan was being made");
    } catch (DomainException e) {
      // A refusal from the rules does not change on a retry: say why and stop.
      database.asSystem(ModuleRole.PLANNING, () -> jobs.failed(job.jobId(), e.getMessage(), clock.now()));
      metrics.increment("waypoint.planning.job.failed", "code", e.code().name());
    } catch (RuntimeException e) {
      log.warn("Plan generation {} for {} on {} failed", job.jobId(), job.depotCode(), job.serviceDate(), e);
      settle(job, "planning failed: " + e.getClass().getSimpleName());
    }
  }

  /** Another attempt while attempts remain, otherwise failed with the reason. */
  private void settle(JobRow job, String why) {
    database.asSystem(ModuleRole.PLANNING, () -> {
      if (job.attempts() < MAX_ATTEMPTS) {
        jobs.requeue(job.jobId(), why);
      } else {
        jobs.failed(job.jobId(), why + " (" + job.attempts() + " attempts)", clock.now());
      }
    });
    metrics.increment("waypoint.planning.job.retried");
  }

  private String body(PlanningRun run) {
    try {
      return json.writeValueAsString(PlanningDrafts.body(run));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot write the job result", e);
    }
  }

  private static long ms(long since) {
    return (System.nanoTime() - since) / 1_000_000L;
  }

  // ---- lifecycle ---------------------------------------------------------------

  @Override
  public synchronized void start() {
    if (running || !enabled || !serving) {
      return;
    }
    running = true;
    for (int i = 0; i < threads; i++) {
      Thread t = new Thread(this::loop, "waypoint-planner-" + (i + 1));
      t.setDaemon(true);
      t.start();
      workers.add(t);
    }
    log.info("Plan generation workers started: {}", threads);
  }

  private void loop() {
    while (running) {
      try {
        if (runPending() == 0) {
          waiting.set(database.asSystem(ModuleRole.PLANNING, jobs::waiting));
          signal.await(POLL_MS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // A database that is down is an outage: keep polling, never die.
        log.warn("Plan generation loop: {}", e.toString());
        try {
          Thread.sleep(POLL_MS);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  @Override
  public synchronized void stop() {
    running = false;
    workers.forEach(Thread::interrupt);
    workers.clear();
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
