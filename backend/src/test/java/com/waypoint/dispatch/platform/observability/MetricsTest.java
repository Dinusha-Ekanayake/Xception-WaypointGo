package com.waypoint.dispatch.platform.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MetricsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final Metrics metrics = new Metrics(registry);

  /** Micrometer's plain gauge holds a weak reference, so a boxed number read NaN after GC. */
  @Test
  void aGaugeSurvivesGarbageCollection() throws Exception {
    AtomicLong depth = new AtomicLong(7);
    metrics.gauge("waypoint.test.depth", depth::get);
    for (int i = 0; i < 5; i++) {
      System.gc();
      Thread.sleep(20);
    }
    double value = registry.get("waypoint.test.depth").gauge().value();
    assertFalse(Double.isNaN(value));
    assertEquals(7.0, value);
    depth.set(9);
    assertEquals(9.0, registry.get("waypoint.test.depth").gauge().value());
  }

  @Test
  void aTimerPublishesP95() {
    for (int i = 1; i <= 100; i++) {
      metrics.record("waypoint.test.latency", i, "kind", "x");
    }
    Timer timer = registry.get("waypoint.test.latency").tag("kind", "x").timer();
    assertEquals(100, timer.count());
    ValueAtPercentile[] percentiles = timer.takeSnapshot().percentileValues();
    assertTrue(Arrays.stream(percentiles).anyMatch(p -> p.percentile() == 0.95));
  }

  @Test
  void timeRecordsEvenWhenTheWorkThrows() {
    assertThrows(
        IllegalStateException.class,
        () ->
            metrics.time(
                "waypoint.test.work",
                () -> {
                  throw new IllegalStateException("boom");
                }));
    assertEquals(1, registry.get("waypoint.test.work").timer().count());
  }
}
