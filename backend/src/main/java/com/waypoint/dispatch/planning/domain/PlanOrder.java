package com.waypoint.dispatch.planning.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One order as Planning sees it: the order-level measures from Ordering joined
 * with the outlet facts from reference data. Capacity reads {@code weightKg}
 * and {@code volumeM3} only, never product lines (AGENTS.md, catalogue rule 1).
 *
 * @param windowOpen the effective window, outlet and mall already intersected
 *     (R-PLN-29). Empty when the intersection is empty
 * @param serviceMinutes the allowance for this brand and dock type (A-16)
 * @param deferralCount how many runs have skipped it (R-PLN-20)
 * @param daysSinceServed days since the outlet last received a delivery
 */
public record PlanOrder(
    UUID orderId,
    String orderRef,
    String outletId,
    String depotCode,
    String brand,
    String district,
    String temperature,
    BigDecimal weightKg,
    BigDecimal volumeM3,
    String dockType,
    boolean vanOnly,
    boolean mallDock,
    Optional<LocalTime> windowOpen,
    Optional<LocalTime> windowClose,
    BigDecimal serviceMinutes,
    int deferralCount,
    int daysSinceServed,
    LocalDate originalRequestedDate) {

  public PlanOrder {
    Objects.requireNonNull(orderId, "orderId");
    Objects.requireNonNull(weightKg, "weightKg");
    Objects.requireNonNull(volumeM3, "volumeM3");
    Objects.requireNonNull(serviceMinutes, "serviceMinutes");
  }

  public TemperatureClass temperatureClass() {
    return TemperatureClass.of(temperature);
  }

  public boolean fresh() {
    return "Fresh".equalsIgnoreCase(brand);
  }

  public boolean hasWindow() {
    return windowOpen.isPresent() && windowClose.isPresent();
  }

  /** Effective window length in minutes, zero when there is none. */
  public long windowMinutes() {
    if (!hasWindow()) {
      return 0;
    }
    return java.time.Duration.between(windowOpen.get(), windowClose.get()).toMinutes();
  }
}
