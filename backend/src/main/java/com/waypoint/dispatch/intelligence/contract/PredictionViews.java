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
  /**
   * One reason a stop is at risk (roadmap MF-08, R-ML-15): {@code TIGHT_WINDOW}
   * with the minutes left, {@code LATE_IN_ROUTE} with the stop's number,
   * {@code OUTLET_HISTORY} with late visits {@code value} of {@code of}.
   */
  public record LateReasonView(String code, int value, int of) {}

  /** @param reasons why it is at risk, strongest first, at most three; empty when nothing stands out */
  public record StopPredictionView(
      UUID orderId,
      UUID tripId,
      int sequence,
      String outletId,
      BigDecimal serviceMinutes,
      BigDecimal lateProbability,
      String modelLabel,
      boolean degraded,
      List<LateReasonView> reasons) {
    public StopPredictionView {
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
  }

  public record PlanPredictionsView(PlanScoringView scoring, List<StopPredictionView> stops) {

    public PlanPredictionsView {
      stops = List.copyOf(stops);
    }
  }

  /** How likely a delivery day is to be kept (issue #224, R-ML-07). */
  public enum OutlookStatus {
    ON_TRACK,
    BUSY,
    AT_RISK,
    /** Little is booked and no forecast reaches the day. */
    TOO_EARLY,
    /** The network does not run that day. */
    CLOSED
  }

  /** One day of a store's date outlook: advice, never a promise; the plan made the day before decides. */
  public record DayOutlookView(LocalDate date, OutlookStatus status, Optional<BigDecimal> load, String reason) {}

  /**
   * A store's outlook for the days it may choose. Built from the depot's totals
   * only, never another outlet's orders.
   *
   * @param forecast whether a demand forecast reached any of the days
   * @param modelLabel what made that forecast, or {@code none}
   */
  public record DateOutlookView(
      String outletId,
      LocalDate from,
      LocalDate to,
      List<DayOutlookView> days,
      boolean forecast,
      String modelLabel,
      boolean degraded) {
    public DateOutlookView {
      days = List.copyOf(days);
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

  /**
   * One route as the model server read it when its plan was scored, and each
   * stop's outcome of that plan (roadmap MF-05, R-ML-18): {@code route} has the
   * scoring request's own fields, so a model retrains on what it will be given.
   * A label's {@code late} is null when the stop has no trustworthy arrival.
   */
  public record TrainingRouteView(
      UUID planId, String routeId, LocalDate serviceDate, Optional<UUID> referenceVersionId,
      java.util.Map<String, Object> route, List<java.util.Map<String, Object>> labels) {
    public TrainingRouteView {
      route = java.util.Map.copyOf(route);
      labels = List.copyOf(labels);
    }
  }

  public enum OverviewStatus {
    /** A forecast run covers these weeks. */
    READY,
    /** No run yet: the forecast job catches up within the hour (P-29). */
    NONE
  }

  /** One brand's demand in a week; chilled is zero for Style and Tech. */
  public record BrandVolumeView(String brandCode, BigDecimal totalM3, BigDecimal chilledM3) {}

  /**
   * What the depot can carry in the week with each day's own fleet: vehicles in
   * the workshop or unavailable that day carry nothing, each available vehicle
   * runs that day's trip limit (A-40, roadmap T2-01). The counts are the fewest
   * available on any operating day. Days with no status set count every vehicle,
   * so weeks far ahead are still an upper bound until workshop days are booked.
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

  /** One brand of a finished week: the forecast made before the week, beside what was ordered (T2-03). */
  public record BrandAccuracyView(String brandCode, BigDecimal forecastM3, BigDecimal orderedM3) {}

  /**
   * A finished ISO week of a depot (roadmap T2-03, R-ML-13): its brands' forecasts
   * as made before the week began, and what was ordered; {@code wape} is empty
   * when nothing was ordered.
   */
  public record WeekAccuracyView(
      int isoYear, int isoWeek, String modelLabel, BigDecimal forecastM3, BigDecimal orderedM3,
      Optional<BigDecimal> wape, List<BrandAccuracyView> brands) {
    public WeekAccuracyView {
      brands = List.copyOf(brands);
    }
  }

  /** One finished ISO week of a model's backtest: its stops, the labelled ones, and its Brier score. */
  public record BacktestWeekView(int isoYear, int isoWeek, int stops, int labelled, Optional<BigDecimal> brier) {}

  /**
   * How one model did on the predictions it made over the last finished weeks
   * (roadmap MF-04, R-ML-16), and its verdict against the built-in estimate:
   * {@code BETTER}, {@code WORSE}, {@code MIXED}, {@code NOT_ENOUGH} or, for the
   * estimate itself, {@code BASELINE}.
   */
  public record ModelBacktestView(
      String modelLabel, int stops, int labelled, BigDecimal coverage, Optional<BigDecimal> brier,
      Optional<BigDecimal> logLoss, Optional<BigDecimal> calibrationSlope, Optional<BigDecimal> calibrationIntercept,
      String verdict, List<BacktestWeekView> weeks) {
    public ModelBacktestView {
      weeks = List.copyOf(weeks);
    }
  }

  /** The backtest window, Monday to Sunday inclusive, and each model that answered in it. */
  public record BacktestView(LocalDate from, LocalDate to, int minLabels, List<ModelBacktestView> models) {
    public BacktestView {
      models = List.copyOf(models);
    }
  }

  /** The last finished weeks, oldest first, and the error over all of them. */
  public record ForecastAccuracyView(String depotCode, List<WeekAccuracyView> weeks, Optional<BigDecimal> wape) {
    public ForecastAccuracyView {
      weeks = List.copyOf(weeks);
    }
  }

  /**
   * The Forecast screen's read for one depot: the newest run, week by week,
   * with calendar and fleet capacity alongside.
   *
   * @param modelLabel {@code name@version}, {@code deterministic}, or empty with no run
   * @param degraded the deterministic forecast answered because no model could be used
   * @param generatedAt when the newest run was generated, empty with no run
   * @param nextRunAt when the forecast job will next run, as things stand (P-29)
   */
  public record ForecastOverviewView(
      String depotCode,
      OverviewStatus status,
      Optional<String> modelLabel,
      boolean degraded,
      Optional<Instant> generatedAt,
      List<ForecastWeekView> weeks,
      Instant nextRunAt) {

    public ForecastOverviewView {
      weeks = List.copyOf(weeks);
    }
  }
}
