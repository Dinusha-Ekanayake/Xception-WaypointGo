package com.waypoint.dispatch.execution;

import com.waypoint.dispatch.shared.util.Clock;
import com.waypoint.dispatch.warehouse.contract.StockPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A warehouse that reserves everything and a clock the test moves, so real
 * orders exist to be delivered and every time rule can be exercised on purpose.
 */
@TestConfiguration
class ExecutionTestConfig {

  @Bean
  @Primary
  StockPort reservingStockPort() {
    return new ReservingStockPort();
  }

  @Bean
  @Primary
  MovableClock executionClock() {
    return new MovableClock();
  }

  static final class ReservingStockPort implements StockPort {
    @Override
    public PlacementResult placeOrder(PlacementRequest request) {
      return reserve(request.lines());
    }

    @Override
    public PlacementResult amendOrder(String warehouseOrderRef, PlacementRequest request) {
      return reserve(request.lines());
    }

    @Override
    public ConfirmResult confirmReservation(String warehouseOrderRef) {
      return new Expired("Execution tests never hold a partial reservation");
    }

    private static PlacementResult reserve(List<StockLine> lines) {
      int items = lines.stream().mapToInt(StockLine::quantity).sum();
      return new Reserved(
          "WH-" + UUID.randomUUID(),
          new BigDecimal(items).multiply(new BigDecimal("2.500")),
          new BigDecimal(items).multiply(new BigDecimal("0.0100")),
          "ambient",
          items);
    }
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
}
