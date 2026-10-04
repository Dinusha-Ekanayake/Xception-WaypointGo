package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.config.RelayProperties;
import com.waypoint.dispatch.platform.messaging.OutboxRelay.Backlog;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The thread that keeps {@link OutboxRelay} running.
 *
 * <p>It delivers until the outbox has nothing due, then waits for a commit in
 * this process ({@link RelaySignal}) or the poll interval, whichever is first.
 * A failing pass is logged and retried with a growing pause, never fatal: a
 * database that is down is an outage, and the relay resumes when it returns.
 *
 * <p>Off with {@code RELAY_ENABLED=false}, which tests use so that delivery
 * happens only when a test asks for it. It also stays off for a CLI command
 * such as {@code migrate}: the schema may not exist yet and the process is
 * about to exit.
 */
@Component
@ConditionalOnProperty(name = "app.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayWorker implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);
  private static final Duration GAUGE_INTERVAL = Duration.ofSeconds(15);
  private static final Duration MAX_PAUSE = Duration.ofSeconds(30);

  private final OutboxRelay relay;
  private final RelaySignal signal;
  private final RelayProperties settings;
  private final Clock clock;
  private final boolean serving;
  private final AtomicLong lagSeconds = new AtomicLong();
  private final AtomicLong open = new AtomicLong();
  private final AtomicLong dead = new AtomicLong();

  private volatile boolean running;
  private Thread thread;
  private Instant gaugesReadAt = Instant.EPOCH;

  public OutboxRelayWorker(
      OutboxRelay relay,
      RelaySignal signal,
      RelayProperties settings,
      Clock clock,
      Metrics metrics,
      ApplicationArguments arguments) {
    this.relay = relay;
    this.signal = signal;
    this.settings = settings;
    this.clock = clock.realTime();
    this.serving = arguments.getNonOptionArgs().isEmpty();
    // PLT-02 and PLT-03: the age of the oldest undelivered event, and the dead letters.
    metrics.gauge("waypoint.outbox.lag_seconds", lagSeconds::get);
    metrics.gauge("waypoint.outbox.open", open::get);
    metrics.gauge("waypoint.outbox.dead", dead::get);
  }

  @Override
  public synchronized void start() {
    if (running || !serving) {
      return;
    }
    running = true;
    thread = new Thread(this::loop, "waypoint-relay");
    thread.setDaemon(true);
    thread.start();
    log.info(
        "Outbox relay started: batch {}, poll {}, {} attempts before dead letter",
        settings.batchSize(), settings.pollInterval(), settings.maxAttempts());
  }

  private void loop() {
    int failures = 0;
    while (running) {
      try {
        int delivered = relay.deliverBatch();
        refreshGauges();
        failures = 0;
        if (delivered == 0) {
          signal.await(settings.pollInterval());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        failures++;
        Duration pause = pauseAfter(failures);
        log.warn("Outbox relay pass failed ({} in a row), pausing {}: {}", failures, pause, e.toString());
        try {
          Thread.sleep(pause.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private Duration pauseAfter(int failures) {
    Duration pause = settings.pollInterval().multipliedBy(1L << Math.min(failures, 6));
    return pause.compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : pause;
  }

  private void refreshGauges() {
    Instant now = clock.now();
    if (Duration.between(gaugesReadAt, now).abs().compareTo(GAUGE_INTERVAL) < 0) {
      return;
    }
    gaugesReadAt = now;
    Backlog backlog = relay.backlog();
    open.set(backlog.open());
    dead.set(backlog.dead());
    lagSeconds.set(
        backlog.oldestOpen().map(oldest -> Math.max(0, Duration.between(oldest, now).toSeconds())).orElse(0L));
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (thread != null) {
      thread.interrupt();
      try {
        // A delivery in flight is safe to abandon: its lease lapses and it is redelivered.
        thread.join(5000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      thread = null;
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
