package com.waypoint.dispatch.platform.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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

  /** Several of the same thing at once, such as the sessions one change revoked. */
  public void count(String name, long amount, String... tags) {
    registry.counter(name, tags).increment(amount);
  }

  /**
   * A latency. Publishes p95 and a histogram, because the SLOs in
   * SYSTEM-ARCHITECTURE section 6.7 are p95 targets and an average hides the tail.
   */
  public void record(String name, long durationMs, String... tags) {
    timer(name, tags).record(durationMs, TimeUnit.MILLISECONDS);
  }

  /** Times {@code work}, whether it returns or throws. */
  public <T> T time(String name, Supplier<T> work, String... tags) {
    long start = System.nanoTime();
    try {
      return work.get();
    } finally {
      timer(name, tags).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * A value read when scraped, such as a queue depth or days of calendar left.
   *
   * <p>Takes a supplier and holds it strongly. Micrometer's plain {@code gauge}
   * keeps only a weak reference to the number it is given, so a boxed value is
   * collected and the gauge reads NaN from then on.
   */
  public void gauge(String name, Supplier<Number> value, String... tags) {
    Gauge.builder(name, value).tags(tags).strongReference(true).register(registry);
  }

  private Timer timer(String name, String... tags) {
    return Timer.builder(name)
        .tags(tags)
        .publishPercentiles(0.95)
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofMillis(1))
        .maximumExpectedValue(Duration.ofSeconds(30))
        .register(registry);
  }
}
