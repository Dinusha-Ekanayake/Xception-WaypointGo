package com.waypoint.dispatch.warehouse.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waypoint.dispatch.platform.config.WarehouseProperties;
import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.platform.time.AdjustedClock;
import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.warehouse.domain.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** DEMO-08: the warehouse circuit is timed on real time, whatever the demo clock does. */
class WarehouseCircuitClockTest {

  @Test
  void movingTheDemoClockNeitherOpensNorClosesTheCircuit() {
    AtomicReference<Instant> real = new AtomicReference<>(Instant.parse("2026-10-05T04:00:00Z"));
    AtomicLong offset = new AtomicLong();
    Clock clock = new AdjustedClock(real::get, () -> offset::get);
    WarehouseProperties properties = new WarehouseProperties(
        "http://127.0.0.1:9", "key", 500, 500, 500, Duration.ofHours(2), 3, Duration.ofSeconds(30),
        Duration.ofMinutes(3), null, Duration.ofMinutes(5));
    WarehouseHttpClient client =
        new WarehouseHttpClient(properties, new ObjectMapper(), new Metrics(new SimpleMeterRegistry()), clock);

    for (int i = 0; i < 3; i++) {
      client.products(1, 1);
    }
    assertEquals(CircuitBreaker.State.OPEN, client.circuitState());

    offset.set(Duration.ofDays(1).toSeconds());
    assertEquals(CircuitBreaker.State.OPEN, client.circuitState(), "a day forward on the demo clock is not 30 s");

    offset.set(-Duration.ofDays(1).toSeconds());
    real.set(real.get().plusSeconds(31));
    assertEquals(CircuitBreaker.State.HALF_OPEN, client.circuitState(), "a day back does not hold it open");
  }
}
