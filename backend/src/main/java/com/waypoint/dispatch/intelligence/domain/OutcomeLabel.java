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
 *
 * <p>A stop with no trustworthy tap may still be labelled from the vehicle's
 * GPS (R-ML-12): the first good fix within {@link #GEOFENCE_M} of an outlet
 * whose location is exact, between leaving the previous stop and finishing
 * this one, is taken as the arrival. A district centre is too rough for that.
 */
public final class OutcomeLabel {
  private OutcomeLabel() {}

  /** Where a label came from. Positions (F-08) with a geofence come later. */
  public enum Source {
    DEVICE,
    GEOFENCE,
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

  /** How near a fix must be to an outlet to count as the vehicle being there. */
  public static final double GEOFENCE_M = 150;

  /** One GPS fix of the vehicle. */
  public record Fix(Instant at, double latitude, double longitude, boolean lowQuality) {}

  /**
   * The tap's label when it is trustworthy, else the arrival the GPS shows at
   * an outlet with an exact location, else no label.
   *
   * @param outlet the outlet's latitude and longitude when its location is exact, else empty
   * @param fixes the vehicle's fixes between leaving the previous stop and finishing this one, oldest first
   * @param windowClose late means arriving after this
   */
  public static Label of(
      Optional<Instant> arrivedAt, boolean timingUncertain, Optional<Integer> lateMinutes,
      Optional<double[]> outlet, java.util.List<Fix> fixes, Instant windowClose) {
    Label tapped = of(arrivedAt, timingUncertain, lateMinutes);
    if (tapped.source() != Source.NONE || outlet.isEmpty()) {
      return tapped;
    }
    for (Fix f : fixes) {
      if (!f.lowQuality() && metres(f.latitude(), f.longitude(), outlet.get()[0], outlet.get()[1]) <= GEOFENCE_M) {
        return new Label(Optional.of(f.at().isAfter(windowClose)), Source.GEOFENCE);
      }
    }
    return tapped;
  }

  /** Great-circle distance in metres. */
  static double metres(double lat1, double lon1, double lat2, double lon2) {
    double r = 6_371_000;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  /** The share of stops with a label, 0 to 1; 1 with no stops, so nothing is flagged on an empty day. */
  public static double coverage(long labelled, long stops) {
    return stops == 0 ? 1.0 : (double) labelled / stops;
  }
}
