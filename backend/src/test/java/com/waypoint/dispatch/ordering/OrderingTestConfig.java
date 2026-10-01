package com.waypoint.dispatch.ordering;

import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.warehouse.contract.StockPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A warehouse the test scripts and a clock the test moves. */
@TestConfiguration
class OrderingTestConfig {

  @Bean
  @Primary
  ScriptedStockPort scriptedStockPort() {
    return new ScriptedStockPort();
  }

  @Bean
  @Primary
  MovableClock movableClock() {
    return new MovableClock();
  }

  static final class MovableClock implements Clock {
    private final AtomicReference<Instant> at = new AtomicReference<>();

    void set(Instant instant) {
      at.set(instant);
    }

    void reset() {
      at.set(null);
    }

    @Override
    public Instant now() {
      Instant fixed = at.get();
      return fixed == null ? Instant.now() : fixed;
    }
  }

  static final class ScriptedStockPort implements StockPort {
    final AtomicInteger calls = new AtomicInteger();
    private volatile Function<List<StockLine>, PlacementResult> next = ScriptedStockPort::reserveAll;
    private volatile long delayMs;

    void answer(Function<List<StockLine>, PlacementResult> script) {
      this.next = script;
    }

    void delay(long ms) {
      this.delayMs = ms;
    }

    void reset() {
      next = ScriptedStockPort::reserveAll;
      delayMs = 0;
      calls.set(0);
    }

    static PlacementResult reserveAll(List<StockLine> lines) {
      int items = lines.stream().mapToInt(StockLine::quantity).sum();
      return new Reserved(
          "WH-" + java.util.UUID.randomUUID(),
          new BigDecimal(items).multiply(new BigDecimal("2.500")),
          new BigDecimal(items).multiply(new BigDecimal("0.0100")),
          "ambient",
          items);
    }

    @Override
    public PlacementResult placeOrder(String orderRef, List<StockLine> lines) {
      return respond(lines);
    }

    @Override
    public PlacementResult amendOrder(String warehouseOrderRef, List<StockLine> lines) {
      return respond(lines);
    }

    private PlacementResult respond(List<StockLine> lines) {
      calls.incrementAndGet();
      if (delayMs > 0) {
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return next.apply(lines);
    }
  }
}
