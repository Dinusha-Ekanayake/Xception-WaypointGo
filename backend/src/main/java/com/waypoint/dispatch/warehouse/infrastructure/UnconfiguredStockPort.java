package com.waypoint.dispatch.warehouse.infrastructure;

import com.waypoint.dispatch.platform.observability.Metrics;
import com.waypoint.dispatch.warehouse.contract.StockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * The warehouse port when no API key is configured: every call is unavailable.
 *
 * <p>This is the branch {@code WarehouseProperties} describes, not a stand-in:
 * with no key there is no warehouse to reach, so placement must degrade to
 * {@code STOCK_UNKNOWN} and say so (decision D-G, architecture rule 9) rather
 * than fail or pretend to reserve. Stock is never assumed (R-STK-05).
 *
 * <p>Registered only while the key is blank. The HTTP adapter
 * ({@code WarehouseStockPort}) registers under the opposite condition, so exactly one {@link StockPort} exists. A key
 * set with no HTTP adapter deployed is a startup failure naming the missing
 * bean, which is the intended answer to configuration the code cannot honour.
 */
@Component
@Conditional(WarehouseKeyAbsent.class)
public class UnconfiguredStockPort implements StockPort {
  static final String REASON = "warehouse not configured: app.warehouse.api-key is blank";

  private static final Logger log = LoggerFactory.getLogger(UnconfiguredStockPort.class);

  private final Metrics metrics;

  public UnconfiguredStockPort(Metrics metrics) {
    this.metrics = metrics;
    log.warn("Warehouse API key is absent: orders will be saved as STOCK_UNKNOWN");
  }

  @Override
  public PlacementResult placeOrder(PlacementRequest request) {
    return unavailable("place");
  }

  @Override
  public PlacementResult amendOrder(String warehouseOrderRef, PlacementRequest request) {
    return unavailable("amend");
  }

  @Override
  public ConfirmResult confirmReservation(String warehouseOrderRef) {
    return unavailable("confirm");
  }

  private Unavailable unavailable(String operation) {
    metrics.increment(
        "waypoint.warehouse.unavailable", "operation", operation, "reason", "unconfigured");
    return new Unavailable(REASON);
  }
}
