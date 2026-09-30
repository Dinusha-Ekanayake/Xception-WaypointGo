package com.waypoint.dispatch.warehouse.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * The cached product catalogue. Waypoint caches it; it does not own it.
 *
 * <p>There is deliberately no stock figure here: stock is queried through the
 * warehouse, never held as a second copy (R-STK-04). Unit weight and volume are
 * a reconstruction accurate to about 1%, so they are for display only and never
 * decide capacity.
 */
public final class CatalogueViews {
  private CatalogueViews() {}

  /**
   * @param verifiedRealSku false on every reconstructed row; the UI must label
   *     such a product "inferred" (CAT-06)
   * @param temperature {@code chilled} or {@code ambient} once the warehouse
   *     publishes it (decision D-M); empty until then
   */
  public record ProductView(
      String productId,
      String brandCode,
      BigDecimal unitWeightKg,
      BigDecimal unitVolumeM3,
      Optional<String> temperature,
      boolean verifiedRealSku,
      String basis) {}

  /**
   * Shown as degraded when stale, never as current (CAT-01).
   *
   * @param circuitState {@code closed}, {@code open} or {@code half_open}
   */
  public record CatalogueStatusView(
      Optional<Instant> syncedAt,
      long ageSeconds,
      boolean stale,
      int productCount,
      String circuitState) {}
}
