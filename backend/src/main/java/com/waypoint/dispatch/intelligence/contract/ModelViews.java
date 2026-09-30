package com.waypoint.dispatch.intelligence.contract;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The model registry, and the commands that change it. A prediction always
 * records the model version that produced it, so it can be reproduced.
 */
public final class ModelViews {
  private ModelViews() {}

  public static final String REGISTER = "ml:RegisterModel";
  public static final String ACTIVATE = "ml:ActivateModel";
  public static final String RETIRE = "ml:RetireModel";

  public enum ModelStatus {
    REGISTERED,
    ACTIVE,
    RETIRED
  }

  /** @param kind {@code service_time}, {@code lateness} or {@code demand} */
  public record ModelVersionView(
      String name,
      String version,
      String kind,
      ModelStatus status,
      Optional<LocalDate> trainedFrom,
      Optional<LocalDate> trainedTo,
      Instant registeredAt) {}

  public interface ModelQuery {

    Optional<ModelVersionView> activeModel(String kind);

    List<ModelVersionView> models();
  }

  public record RegisterModel(
      String name, String version, String kind, LocalDate trainedFrom, LocalDate trainedTo) {}

  public record ActivateModel(String name, String version) {}

  public record RetireModel(String name, String version, String reason) {}
}
