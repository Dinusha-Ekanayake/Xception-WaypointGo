package com.waypoint.dispatch.execution.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.waypoint.dispatch.shared.error.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PositionPolicyTest {
  private static final Instant NOW = Instant.parse("2026-10-03T06:00:00Z");
  private static PositionFix fix(Instant at, String lat, String lon, String accuracy) {
    return new PositionFix(at, new BigDecimal(lat), new BigDecimal(lon),
        accuracy == null ? null : new BigDecimal(accuracy), null, null);
  }
  private static PositionFix fix(Instant at) { return fix(at, "7.000000", "80.000000", "10"); }

  @Test
  void acceptsOldOfflinePointsAndTheInclusiveSriLankaBounds() {
    assertEquals(2, PositionPolicy.accept(List.of(
        fix(NOW.minusSeconds(86400), "5.8", "79.5", null),
        fix(NOW, "9.9", "82.0", "200")), NOW).size());
  }

  @Test
  void rejectsOutsideBoundsWithoutIncludingCoordinatesInTheError() {
    for (var point : List.of(fix(NOW, "5.799999", "80", "10"), fix(NOW, "9.900001", "80", "10"),
        fix(NOW, "7", "79.499999", "10"), fix(NOW, "7", "82.000001", "10"))) {
      var error = assertThrows(DomainException.class, () -> PositionPolicy.accept(List.of(point), NOW));
      assertEquals(List.of("R-EXE-18"), error.rules());
      assertFalse(error.getMessage().contains(point.latitude().toPlainString()));
    }
  }

  @Test
  void clockSkewLimitIsInclusiveAndOrderingMustBeStrict() {
    assertEquals(1, PositionPolicy.accept(List.of(fix(NOW.plusSeconds(300))), NOW).size());
    assertThrows(DomainException.class, () -> PositionPolicy.accept(List.of(fix(NOW.plusSeconds(301))), NOW));
    assertThrows(DomainException.class, () -> PositionPolicy.accept(List.of(fix(NOW), fix(NOW.minusSeconds(1))), NOW));
    assertThrows(DomainException.class, () -> PositionPolicy.accept(List.of(fix(NOW),
        fix(NOW, "7.1", "80", "10")), NOW));
  }

  @Test
  void exactDuplicateFixIsDroppedButStationaryHeartbeatsAndQualityChangesSurvive() {
    var first = fix(NOW.minusSeconds(30));
    var result = PositionPolicy.accept(List.of(first, first, fix(NOW)), NOW);
    assertEquals(List.of(first, fix(NOW)), result);
    assertEquals(2, PositionPolicy.accept(List.of(fix(NOW.minusSeconds(1), "7", "80", "201"), fix(NOW)), NOW).size());
  }

  @Test
  void lowAccuracyNeverAdvancesLastSeenAndOfflineNeedsAnActiveTrip() {
    var good = fix(NOW.minusSeconds(600));
    var poor = fix(NOW, "7", "80", "200.1");
    assertFalse(PositionPolicy.lowQuality(good));
    assertTrue(PositionPolicy.lowQuality(poor));
    assertEquals(good.recordedAt(), PositionPolicy.lastGood(List.of(good, poor)).orElseThrow().recordedAt());
    assertTrue(PositionPolicy.isOffline(good, NOW, true));
    assertFalse(PositionPolicy.isOffline(fix(NOW.minusSeconds(599)), NOW, true));
    assertFalse(PositionPolicy.isOffline(good, NOW, false));
    assertTrue(PositionPolicy.isOffline(null, NOW, true));
    assertTrue(PositionPolicy.isOffline(poor, NOW, true));
    assertTrue(PositionPolicy.lastGood(List.of(poor)).isEmpty());
  }

  @Test
  void validatesBatchSizeAndOptionalSensorValuesWithoutSilentlyRounding() {
    assertThrows(DomainException.class, () -> PositionPolicy.accept(List.of(), NOW));
    assertThrows(DomainException.class, () -> PositionPolicy.accept(java.util.Collections.nCopies(101, fix(NOW)), NOW));
    for (var point : List.of(fix(NOW, "7.0000001", "80", "10"), fix(NOW, "7", "80", "-1"),
        new PositionFix(NOW, BigDecimal.ONE, BigDecimal.ONE, null, new BigDecimal("360"), null),
        new PositionFix(NOW, new BigDecimal("7"), new BigDecimal("80"), null, null, new BigDecimal("-1")))) {
      assertThrows(DomainException.class, () -> PositionPolicy.accept(List.of(point), NOW));
    }
  }
}
