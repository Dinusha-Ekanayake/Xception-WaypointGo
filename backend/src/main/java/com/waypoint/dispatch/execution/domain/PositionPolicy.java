package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** R-EXE-18/19/22: pure fix acceptance, freshness and direction of travel, with server time supplied by the caller. */
public final class PositionPolicy {
  private PositionPolicy() {}

  public static List<PositionFix> accept(List<PositionFix> points, Instant receivedAt) {
    if (points == null || points.isEmpty() || points.size() > 100) throw invalid("batch_size");
    if (receivedAt == null) throw invalid("server_time");
    List<PositionFix> accepted = new ArrayList<>();
    PositionFix previous = null;
    for (PositionFix point : points) {
      if (point == null || point.recordedAt() == null) throw invalid("recorded_at");
      if (!within(point.latitude(), "5.8", "9.9", 6)
          || !within(point.longitude(), "79.5", "82.0", 6)) throw invalid("bounds");
      if (point.recordedAt().isAfter(receivedAt.plusSeconds(300))) throw invalid("clock_skew");
      if (point.accuracyM() != null && !within(point.accuracyM(), "0", "999999.9", 1)) throw invalid("accuracy");
      if (point.headingDeg() != null && !within(point.headingDeg(), "0", "359.9", 1)) throw invalid("heading");
      if (point.speedKmh() != null && !within(point.speedKmh(), "0", "9999.9", 1)) throw invalid("speed");
      if (previous != null) {
        if (sameFix(previous, point)) continue;
        if (!point.recordedAt().isAfter(previous.recordedAt())) throw invalid("point_order");
      }
      accepted.add(point);
      previous = point;
    }
    return List.copyOf(accepted);
  }

  public static boolean lowQuality(PositionFix point) {
    return point.accuracyM() != null && point.accuracyM().compareTo(new BigDecimal("200")) > 0;
  }

  public static Optional<PositionFix> lastGood(List<PositionFix> points) {
    return points.stream().filter(p -> !lowQuality(p)).max(Comparator.comparing(PositionFix::recordedAt));
  }

  public static boolean isOffline(PositionFix lastGood, Instant now, boolean tripInProgress) {
    return tripInProgress && (lastGood == null || lowQuality(lastGood)
        || Duration.between(lastGood.recordedAt(), now).compareTo(Duration.ofMinutes(10)) >= 0);
  }

  /** R-EXE-22: a fix this close to the latest is GPS jitter, not movement, and says nothing of direction. */
  static final double MIN_MOVE_M = 15;

  /** R-EXE-22: a fix older than this before the latest is a different leg, not the current direction. */
  static final Duration MAX_GAP = Duration.ofMinutes(5);

  private static final double EARTH_RADIUS_M = 6_371_000;

  /**
   * R-EXE-22: the direction of travel, from the latest good fix and the newest
   * earlier good fix at least {@value #MIN_MOVE_M} m from it and at most
   * {@link #MAX_GAP} older. A vehicle standing still keeps the phone's own
   * heading, or none, so a parked truck never spins on jitter.
   *
   * @param newestFirst the vehicle's recent fixes, newest first
   * @return degrees clockwise from north, 0 to 359.9, one decimal
   */
  public static Optional<BigDecimal> travelHeading(List<PositionFix> newestFirst) {
    PositionFix latest = newestFirst.stream().filter(p -> !lowQuality(p)).findFirst().orElse(null);
    if (latest == null) return Optional.empty();
    for (PositionFix earlier : newestFirst) {
      if (lowQuality(earlier) || !earlier.recordedAt().isBefore(latest.recordedAt())) continue;
      if (Duration.between(earlier.recordedAt(), latest.recordedAt()).compareTo(MAX_GAP) > 0) break;
      if (metres(earlier, latest) >= MIN_MOVE_M) return Optional.of(bearing(earlier, latest));
    }
    return Optional.ofNullable(latest.headingDeg());
  }

  /** Great-circle distance in metres. */
  public static double metres(PositionFix a, PositionFix b) {
    double lat1 = Math.toRadians(a.latitude().doubleValue());
    double lat2 = Math.toRadians(b.latitude().doubleValue());
    double dLat = lat2 - lat1;
    double dLon = Math.toRadians(b.longitude().doubleValue() - a.longitude().doubleValue());
    double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
  }

  /** Initial bearing from one fix to the next, rounded to one decimal and kept below 360. */
  static BigDecimal bearing(PositionFix from, PositionFix to) {
    double lat1 = Math.toRadians(from.latitude().doubleValue());
    double lat2 = Math.toRadians(to.latitude().doubleValue());
    double dLon = Math.toRadians(to.longitude().doubleValue() - from.longitude().doubleValue());
    double y = Math.sin(dLon) * Math.cos(lat2);
    double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
    double degrees = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    BigDecimal rounded = BigDecimal.valueOf(degrees).setScale(1, RoundingMode.HALF_UP);
    return rounded.compareTo(new BigDecimal("360")) >= 0 ? new BigDecimal("0.0") : rounded;
  }

  private static boolean sameFix(PositionFix a, PositionFix b) {
    return a.recordedAt().equals(b.recordedAt()) && equal(a.latitude(), b.latitude())
        && equal(a.longitude(), b.longitude()) && equal(a.accuracyM(), b.accuracyM())
        && equal(a.headingDeg(), b.headingDeg()) && equal(a.speedKmh(), b.speedKmh());
  }

  private static boolean equal(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }

  private static boolean within(BigDecimal value, String min, String max, int scale) {
    return value != null && value.compareTo(new BigDecimal(min)) >= 0
        && value.compareTo(new BigDecimal(max)) <= 0 && value.stripTrailingZeros().scale() <= scale;
  }

  private static DomainException invalid(String reason) {
    // Never echo coordinates, sensor values or a recorded timestamp into logs or receipts.
    return new DomainException(ErrorCode.VALIDATION_FAILED, "Invalid position batch: " + reason, List.of("R-EXE-18"));
  }
}
