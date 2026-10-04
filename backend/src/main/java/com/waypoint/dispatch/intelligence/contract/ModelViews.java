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

  /** Service minutes and P(late) per stop, scored over whole planned routes (Datathon Task 1). */
  public static final String DELIVERY_RISK = "delivery_risk";

  /** Weekly total and chilled m3 per depot and brand (Datathon Task 2A). */
  public static final String DEMAND_FORECAST = "demand_forecast";

  public static final List<String> KINDS = List.of(DELIVERY_RISK, DEMAND_FORECAST);

  public enum ModelStatus {
    REGISTERED,
    ACTIVE,
    RETIRED
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
      long rowVersion) {

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
}
