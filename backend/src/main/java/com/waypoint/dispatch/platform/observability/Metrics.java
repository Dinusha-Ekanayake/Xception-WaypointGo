package com.waypoint.dispatch.platform.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The domain-facing metrics API.
 *
 * <p>Every edge case in docs/architecture/EDGE-CASES.md names a detection signal.
 * This is where those signals live, so a case handled in code is also visible in
 * production rather than only in a test.
 *
 * <p>Call sites use this, never Micrometer directly, so the backend can change
 * without touching business code.
 */
@Component
public class Metrics {
  private final MeterRegistry registry;

  public Metrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void increment(String name, String... tags) {
    registry.counter(name, tags).increment();
  }

  public void record(String name, long durationMs, String... tags) {
    Timer.builder(name).tags(tags).register(registry).record(durationMs, TimeUnit.MILLISECONDS);
  }

  public void gauge(String name, Number value, String... tags) {
    registry.gauge(name, io.micrometer.core.instrument.Tags.of(tags), value);
  }
}
