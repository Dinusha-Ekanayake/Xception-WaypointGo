package com.waypoint.dispatch.execution.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** R-EXE-18/19: pure fix acceptance and freshness, with server time supplied by the caller. */
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
