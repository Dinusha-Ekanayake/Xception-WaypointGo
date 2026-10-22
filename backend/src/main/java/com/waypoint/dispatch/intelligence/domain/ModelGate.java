package com.waypoint.dispatch.intelligence.domain;

import java.util.Optional;

/**
 * Whether a model's answer may be used, and if not, the reason to show.
 *
 * <p>A model is used only when serving is configured, a model of that kind is
 * active in the registry, and the serving process reports exactly that model
 * (R-ML-04). Anything else is the deterministic answer, marked degraded with the
 * reason, so a plan never silently carries a model nobody activated, or one that
 * differs from what was registered.
 */
public final class ModelGate {
  private ModelGate() {}

  public record Decision(boolean useModel, Optional<String> reason) {
    static Decision use() {
      return new Decision(true, Optional.empty());
    }

    static Decision degrade(String reason) {
      return new Decision(false, Optional.of(reason));
    }
  }

  /** The name prefix of models trained on the competition data (R-ML-09). */
  public static final String COMPETITION_PREFIX = "datathon-";

  /** True for a model trained on the competition data, by its {@code name} or {@code name@version}. */
  public static boolean competitionModel(String nameOrLabel) {
    return nameOrLabel != null && nameOrLabel.startsWith(COMPETITION_PREFIX);
  }

  /**
   * As {@link #decide(String, boolean, Optional, Served)}, and where models
   * trained on the competition data are not allowed (production), such a model
   * never answers: the deterministic estimate does, and says why (R-ML-09).
   */
  public static Decision decide(
      String kind, boolean configured, Optional<String> activeLabel, Served served, boolean allowCompetitionModels) {
    if (!allowCompetitionModels && activeLabel.isPresent() && competitionModel(activeLabel.get())) {
      return Decision.degrade("models trained on the competition data are not allowed here");
    }
    return decide(kind, configured, activeLabel, served);
  }

  /**
   * @param activeLabel the registry's active model of this kind, {@code name@version}
   * @param served what the serving process reports for this kind, or the reason it could not be asked
   */
  public static Decision decide(
      String kind, boolean configured, Optional<String> activeLabel, Served served) {
    if (!configured) {
      return Decision.degrade("model serving is not configured");
    }
    if (activeLabel.isEmpty()) {
      return Decision.degrade("no " + kind + " model is active");
    }
    if (served.unavailable().isPresent()) {
      return Decision.degrade("model serving is unavailable: " + served.unavailable().get());
    }
    if (served.labels().isEmpty()) {
      return Decision.degrade("model serving has no " + kind + " model loaded");
    }
    // Issue #12, R-ML-39: the service may hold several models of a kind; the active one must be among them.
    if (!served.labels().contains(activeLabel.get())) {
      return Decision.degrade(
          "model serving has " + String.join(", ", served.labels()) + " but the active model is " + activeLabel.get());
    }
    return Decision.use();
  }

  /** What the serving process said it has loaded for one kind: every label, the image's first. */
  public record Served(java.util.List<String> labels, Optional<String> unavailable) {
    public Served {
      labels = java.util.List.copyOf(labels);
    }

    public static Served loaded(String label) {
      return new Served(java.util.List.of(label), Optional.empty());
    }

    public static Served loaded(java.util.Collection<String> labels) {
      return new Served(java.util.List.copyOf(new java.util.LinkedHashSet<>(labels)), Optional.empty());
    }

    public static Served none() {
      return new Served(java.util.List.of(), Optional.empty());
    }

    public static Served unavailable(String why) {
      return new Served(java.util.List.of(), Optional.of(why));
    }
  }
}
