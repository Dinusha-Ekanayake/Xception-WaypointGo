package com.waypoint.dispatch.warehouse.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.warehouse.contract.StockPort;
import com.waypoint.dispatch.warehouse.contract.StockPort.StockLine;
import com.waypoint.dispatch.warehouse.contract.StockPort.Unavailable;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class UnconfiguredStockPortTest {

  private final UnconfiguredStockPort port = new UnconfiguredStockPort(mock(Metrics.class));

  @Test
  void placementIsUnavailableSoTheOrderIsSavedAsStockUnknown() {
    var result = port.placeOrder(
        new StockPort.PlacementRequest(
            java.util.UUID.randomUUID(), "WPO-TEST", "Kandy", List.of(new StockLine("P-1", 2))));

    Unavailable unavailable = assertInstanceOf(Unavailable.class, result);
    assertEquals(UnconfiguredStockPort.REASON, unavailable.reason());
  }

  @Test
  void amendmentIsUnavailableToo() {
    assertInstanceOf(Unavailable.class, port.amendOrder(
        "WH-1", new StockPort.PlacementRequest(java.util.UUID.randomUUID(), "WPO-TEST", "Kandy", List.of())));
  }

  @Test
  void itIsRegisteredOnlyWhileTheKeyIsBlank() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(Metrics.class, () -> mock(Metrics.class))
            .withUserConfiguration(UnconfiguredStockPort.class);

    runner
        .withPropertyValues("app.warehouse.api-key=")
        .run(context -> assertTrue(context.containsBean("unconfiguredStockPort")));
    runner.run(context -> assertTrue(context.containsBean("unconfiguredStockPort")));
    runner
        .withPropertyValues("app.warehouse.api-key=secret")
        .run(context -> assertFalse(context.containsBean("unconfiguredStockPort")));
  }
}
