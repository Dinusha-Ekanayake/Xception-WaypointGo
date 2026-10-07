package com.waypoint.dispatch.intelligence.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * What happened at a stop, as a label a model can learn from (roadmap MF-02,
 * R-ML-12). Late means the arrival came after the window closed. The label
 * comes only from an arrival the driver recorded on time: a missing arrival,
 * or one recorded after a reconnect whose time is the replay's, gives no label,
 * never "not late". Rushed drivers skip the tap, so missing labels lean late,
 * and the coverage is reported beside every metric. Pure.
 */
public final class OutcomeLabel {
  private OutcomeLabel() {}

  /** Where a label came from. Positions (F-08) with a geofence come later. */
  public enum Source {
    DEVICE,
    NONE;

    public String code() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /** @param late empty when there is no trustworthy arrival */
  public record Label(Optional<Boolean> late, Source source) {}

  public static Label of(Optional<Instant> arrivedAt, boolean timingUncertain, Optional<Integer> lateMinutes) {
    if (arrivedAt.isEmpty() || timingUncertain) {
      return new Label(Optional.empty(), Source.NONE);
    }
    return new Label(Optional.of(lateMinutes.orElse(0) > 0), Source.DEVICE);
  }

  /** The share of stops with a label, 0 to 1; 1 with no stops, so nothing is flagged on an empty day. */
  public static double coverage(long labelled, long stops) {
    return stops == 0 ? 1.0 : (double) labelled / stops;
  }
}
