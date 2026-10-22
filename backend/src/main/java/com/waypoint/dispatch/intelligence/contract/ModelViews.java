package com.waypoint.dispatch.intelligence.contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The model registry, and the commands that change it. A prediction always
 * records the model version that produced it, so it can be reproduced.
 *
 * <p>A model is used only while it is active, and only when the serving process
 * reports the same name and version; anything else is the deterministic answer,
 * marked degraded (R-ML-04).
 */
public final class ModelViews {
  private ModelViews() {}

  public static final String REGISTER = "ml:RegisterModel";
  public static final String ACTIVATE = "ml:ActivateModel";
  public static final String RETIRE = "ml:RetireModel";
  /** Issue #12, R-ML-40: a registered model scores every published plan beside the active one, and never answers. */
  public static final String SHADOW = "ml:ShadowModel";

  /** Service minutes and P(late) per stop, scored over whole planned routes (Datathon Task 1). */
  public static final String DELIVERY_RISK = "delivery_risk";

  /** Weekly total and chilled m3 per depot and brand (Datathon Task 2A). */
  public static final String DEMAND_FORECAST = "demand_forecast";

  public static final List<String> KINDS = List.of(DELIVERY_RISK, DEMAND_FORECAST);

  public enum ModelStatus {
    REGISTERED,
    ACTIVE,
    /** Scored beside the active model of its kind, never answering (R-ML-40). At most one per kind. */
    SHADOW,
    RETIRED
  }

  /** How a shadow got there (issue #12). */
  public enum ShadowRole {
    /** A model retraining produced, made the shadow to prove itself before a person promotes it. */
    CANDIDATE,
    /** The model a promotion replaced, kept as the challenger the system may put back (R-ML-42). */
    REPLACED
  }

  /**
   * @param kind {@link #DELIVERY_RISK} or {@link #DEMAND_FORECAST}
   * @param metrics validation figures as registered, for example {@code late_logloss}
   */
  public record ModelVersionView(
      String name,
      String version,
      String kind,
      ModelStatus status,
      Map<String, BigDecimal> metrics,
      Optional<LocalDate> trainedFrom,
      Optional<LocalDate> trainedTo,
      Instant registeredAt,
      Optional<Instant> activatedAt,
      Optional<String> retiredReason,
      long rowVersion,
      Optional<ShadowRole> shadowRole,
      Optional<Instant> shadowedAt) {

    public ModelVersionView {
      metrics = Map.copyOf(metrics);
    }

    /** How a prediction names its model: {@code name@version}. */
    public String label() {
      return name + "@" + version;
    }
  }

  public interface ModelQuery {

    Optional<ModelVersionView> activeModel(String kind);

    List<ModelVersionView> models();
  }

  public record RegisterModel(
      String name,
      String version,
      String kind,
      LocalDate trainedFrom,
      LocalDate trainedTo,
      Map<String, BigDecimal> metrics) {}

  public record ActivateModel(String name, String version) {}

  public record RetireModel(String name, String version, String reason) {}

  public record ShadowModel(String name, String version) {}

  /**
   * Issue #12, R-ML-40: one night's comparison of the shadow with the model
   * that answered, on the same labelled stops of the window.
   *
   * @param incumbentLabel the active model's label, or {@code deterministic} when none was active
   * @param verdict {@code BETTER}, {@code WORSE}, {@code MIXED} or {@code NOT_ENOUGH}, for the shadow
   * @param action {@code rolled_back} when the system put the replaced model back (R-ML-42)
   */
  public record ShadowReviewView(
      String kind,
      String modelLabel,
      ShadowRole shadowRole,
      String incumbentLabel,
      LocalDate reviewedOn,
      LocalDate windowFrom,
      LocalDate windowTo,
      int stops,
      Optional<BigDecimal> shadowBrier,
      Optional<BigDecimal> shadowLogLoss,
      Optional<BigDecimal> incumbentBrier,
      Optional<BigDecimal> incumbentLogLoss,
      String verdict,
      Optional<String> action) {}
}
