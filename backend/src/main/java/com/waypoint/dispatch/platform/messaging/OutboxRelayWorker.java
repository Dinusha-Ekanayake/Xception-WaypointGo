package com.waypoint.dispatch.platform.messaging;

import com.waypoint.dispatch.platform.config.OutboxProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Runs {@link OutboxRelay} for as long as the process serves requests.
 *
 * <p>Drains while there is work, then waits {@code pollInterval}. A pass that
 * fails, because the database is down, is counted and logged and the next one
 * starts on schedule: the relay degrades to "events wait", which the lag gauge
 * shows, and catches up when the database returns. Every instance runs one; the
 * {@code SKIP LOCKED} claim is what keeps them from delivering the same event.
 *
 * <p>Not started for the one-shot commands ({@code migrate} and the rest), which
 * run with no web application and may be creating the tables the relay reads.
 * Off with {@code OUTBOX_ENABLED=false}, which tests use.
 */
@Component
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayWorker implements SmartInitializingSingleton, DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);
  /** A backlog is drained in bursts, but one pass never starves the rest of the loop forever. */
  private static final int MAX_PASSES_PER_TICK = 20;

  private final OutboxRelay relay;
  private final OutboxProperties properties;
  private final Metrics metrics;
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

  public OutboxRelayWorker(OutboxRelay relay, OutboxProperties properties, Metrics metrics) {
    this.relay = relay;
    this.properties = properties;
    this.metrics = metrics;
  }

  @Override
  public void afterSingletonsInstantiated() {
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("waypoint-outbox-");
    scheduler.initialize();
    scheduler.scheduleWithFixedDelay(this::tick, properties.pollInterval());
    log.info("Outbox relay polling every {}", properties.pollInterval());
  }

  void tick() {
    try {
      for (int pass = 0; pass < MAX_PASSES_PER_TICK; pass++) {
        if (relay.runOnce() == 0) {
          return;
        }
      }
    } catch (RuntimeException e) {
      metrics.increment("waypoint.outbox.relay_failed");
      log.warn("Outbox relay pass failed: {}", OutboxRelay.describe(e));
    }
  }

  @Override
  public void destroy() {
    scheduler.shutdown();
  }
}
