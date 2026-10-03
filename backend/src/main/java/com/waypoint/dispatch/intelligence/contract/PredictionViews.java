package com.waypoint.dispatch.intelligence.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What Intelligence has predicted, and how. Every answer names the model that
 * produced it, or {@code deterministic}, and says when a configured model could
 * not be used, so a screen can say so (rule 9).
 */
public final class PredictionViews {
  private PredictionViews() {}

  /** The model label of an answer that came from no model. */
  public static final String DETERMINISTIC = "deterministic";

  public enum ScoringStatus {
    /** Waiting for the scoring job. */
    PENDING,
    /** Scored by an active model. */
    SCORED,
    /** Scored by the deterministic estimator, because no model could be used. */
    DEGRADED
  }

  /**
   * How a published plan's stops were scored.
   *
   * @param modelLabel {@code name@version}, or {@code deterministic}
   * @param roadConditions {@code used}, or {@code fallback} when a service date had no road
   *     conditions and the model without them answered
   * @param reason why no model was used, when it was not
   */
  public record PlanScoringView(
      UUID planId,
      String depotCode,
      LocalDate serviceDate,
      ScoringStatus status,
      Optional<String> modelLabel,
      Optional<String> roadConditions,
      Optional<String> reason,
      Optional<Instant> scoredAt) {

    /** True unless an active model scored the plan: what the plan screen reports. */
    public boolean withoutPredictor() {
      return status != ScoringStatus.SCORED;
    }
  }

  /** One stop's expected service minutes and probability of arriving after its window closes. */
  public record StopPredictionView(
      UUID orderId,
      UUID tripId,
      int sequence,
      String outletId,
      BigDecimal serviceMinutes,
      BigDecimal lateProbability,
      String modelLabel,
      boolean degraded) {}

  public record PlanPredictionsView(PlanScoringView scoring, List<StopPredictionView> stops) {

    public PlanPredictionsView {
      stops = List.copyOf(stops);
    }
  }

  /**
   * R-RCP-06: the probability that an order is supplied on its scheduled day.
   *
   * @param basis what the number rests on, for example {@code planned} or {@code deferral_rate}
   */
  public record SupplyProbabilityView(
      UUID orderId,
      LocalDate scheduledDate,
      BigDecimal probability,
      String basis,
      String modelLabel,
      boolean degraded) {}

  /**
   * One delivery's actuals, as a model trains on them. Waiting for the window is
   * kept apart from service, so a long wait never teaches the model a long
   * service (EXE-18).
   */
  public record TrainingDeliveryView(
      UUID deliveryId,
      UUID orderId,
      String outletId,
      String vehicleId,
      LocalDate serviceDate,
      Optional<LocalTime> plannedArrival,
      LocalTime windowOpen,
      LocalTime windowClose,
      Optional<Instant> arrivedAt,
      Optional<Instant> completedAt,
      Optional<Integer> waitMinutes,
      Optional<BigDecimal> serviceMinutes,
      Optional<Integer> lateMinutes,
      String outcome,
      boolean timingUncertain) {}

  public enum OverviewStatus {
    /** A forecast run covers these weeks. */
    READY,
    /** No run yet: the forecast job catches up within the hour (P-29). */
    NONE
  }

  /** One brand's demand in a week; chilled is zero for Style and Tech. */
  public record BrandVolumeView(String brandCode, BigDecimal totalM3, BigDecimal chilledM3) {}

  /**
   * What the depot's reference fleet can carry in the week: every vehicle, two
   * trips a day, each operating day (A-40). An upper bound, not availability.
   */
  public record WeekCapacityView(
      int vehicles, int refrigeratedVehicles, BigDecimal fleetM3, BigDecimal refrigeratedM3) {}

  /**
   * One ISO week of the forecast with the calendar around it.
   *
   * @param festival the festival the week ramps towards, if any
   * @param generatedDays days past the supplied calendar, from the extension policy (R-CAL-03)
   */
  public record ForecastWeekView(
      int isoYear,
      int isoWeek,
      LocalDate weekStart,
      int operatingDays,
      int holidayDays,
      int paydays,
      Optional<String> festival,
      int generatedDays,
      List<BrandVolumeView> brands,
      BigDecimal totalM3,
      BigDecimal chilledM3,
      WeekCapacityView capacity) {

    public ForecastWeekView {
      brands = List.copyOf(brands);
    }
  }

  /**
   * The Forecast screen's read for one depot: the newest run, week by week,
   * with calendar and fleet capacity alongside.
   *
   * @param modelLabel {@code name@version}, {@code deterministic}, or empty with no run
   * @param degraded the deterministic forecast answered because no model could be used
   */
  public record ForecastOverviewView(
      String depotCode,
      OverviewStatus status,
      Optional<String> modelLabel,
      boolean degraded,
      Optional<Instant> generatedAt,
      List<ForecastWeekView> weeks) {

    public ForecastOverviewView {
      weeks = List.copyOf(weeks);
    }
  }
}
