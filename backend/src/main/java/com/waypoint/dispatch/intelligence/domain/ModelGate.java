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
    if (served.label().isEmpty()) {
      return Decision.degrade("model serving has no " + kind + " model loaded");
    }
    if (!served.label().get().equals(activeLabel.get())) {
      return Decision.degrade(
          "model serving has " + served.label().get() + " but the active model is " + activeLabel.get());
    }
    return Decision.use();
  }

  /** What the serving process said it has loaded for one kind. */
  public record Served(Optional<String> label, Optional<String> unavailable) {
    public static Served loaded(String label) {
      return new Served(Optional.of(label), Optional.empty());
    }

    public static Served none() {
      return new Served(Optional.empty(), Optional.empty());
    }

    public static Served unavailable(String why) {
      return new Served(Optional.empty(), Optional.of(why));
    }
  }
}
