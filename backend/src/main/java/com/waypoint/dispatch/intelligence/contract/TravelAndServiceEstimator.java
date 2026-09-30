package com.waypoint.dispatch.intelligence.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Estimates Planning and the store UI need, behind a port.
 *
 * <p>A deterministic implementation is always available, so Planning never
 * stops for want of a model. When a model is configured but unreachable, the
 * deterministic answer is returned with {@code degraded = true}, and the plan is
 * marked "planned without predictor" (architecture rule 9). A remote model
 * server is an adapter behind this interface, which is what keeps Intelligence
 * extractable (ADR-001).
 */
public interface TravelAndServiceEstimator {

  ServiceTimeEstimate serviceTime(
      String outletId, String brandCode, String dockType, LocalDate serviceDate);

  LatenessEstimate lateness(String outletId, LocalTime plannedArrival, LocalDate serviceDate);

  Optional<DemandForecast> demandForecast(
      String depotCode, String brandCode, int isoYear, int isoWeek);

  /**
   * @param modelVersion {@code deterministic} for the default implementation
   * @param degraded true when a configured model could not be used
   */
  record ServiceTimeEstimate(BigDecimal minutes, String modelVersion, boolean degraded) {}

  record LatenessEstimate(BigDecimal probability, String modelVersion, boolean degraded) {}

  /** Chilled volume is zero for Style and Tech. */
  record DemandForecast(
      String depotCode,
      String brandCode,
      int isoYear,
      int isoWeek,
      BigDecimal totalVolumeM3,
      BigDecimal chilledVolumeM3,
      String modelVersion) {}
}
