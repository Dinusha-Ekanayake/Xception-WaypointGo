package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.waypoint.dispatch.intelligence.domain.KpiBaseline.StopFact;
import com.waypoint.dispatch.shared.error.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class KpiBaselineTest {
  static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
  static final LocalDate THURSDAY = LocalDate.of(2026, 10, 8);

  static Instant at(String time) {
    return THURSDAY.atTime(java.time.LocalTime.parse(time)).atZone(COLOMBO).toInstant();
  }

  @Test
  void aWeekIsCountedFromItsMonday() {
    assertEquals(LocalDate.of(2026, 10, 5), KpiBaseline.weekOf(THURSDAY));
    assertEquals(LocalDate.of(2026, 10, 5), KpiBaseline.weekOf(LocalDate.of(2026, 10, 5)));
  }

  @Test
  void freshOnTimeBeforeEightAndAdoptionFromTheStopsTheAppFinished() {
    var week = KpiBaseline.count(List.of(
        new StopFact(THURSDAY, true, "delivered", 0, at("06:40"), true),
        new StopFact(THURSDAY, true, "delivered", 25, at("08:20"), true),
        new StopFact(THURSDAY, true, "partial", null, at("07:10"), false),
        new StopFact(THURSDAY, true, "failed", null, null, true),
        new StopFact(THURSDAY, false, "delivered", 40, at("11:00"), true)), COLOMBO)
        .get(LocalDate.of(2026, 10, 5));

    // Fresh handed over: 3 stops, 2 by the window's close, 2 before 08:00.
    assertEquals(new BigDecimal("66.7"), week.get(KpiBaseline.FRESH_ON_TIME).value());
    assertEquals(3, week.get(KpiBaseline.FRESH_ON_TIME).stops());
    assertEquals(new BigDecimal("66.7"), week.get(KpiBaseline.FRESH_BEFORE_8).value());
    // Every finished stop counts for adoption: 4 of 5 recorded on the phone.
    assertEquals(new BigDecimal("80.0"), week.get(KpiBaseline.ADOPTION).value());
  }

  @Test
  void aWeekWithNoFreshStopHasNoFreshFigureNotAZero() {
    var week = KpiBaseline.count(List.of(new StopFact(THURSDAY, false, "delivered", 0, at("06:00"), true)), COLOMBO)
        .get(LocalDate.of(2026, 10, 5));
    assertNull(week.get(KpiBaseline.FRESH_ON_TIME));
    assertEquals(new BigDecimal("100.0"), week.get(KpiBaseline.ADOPTION).value());
  }

  @Test
  void aFigureIsRefusedWhenItCannotBeTrue() {
    LocalDate monday = LocalDate.of(2026, 10, 5);
    assertThrows(DomainException.class, () -> KpiBaseline.check("smiles_per_trip", monday, BigDecimal.ONE));
    assertThrows(DomainException.class, () -> KpiBaseline.check(KpiBaseline.CALLS, THURSDAY, BigDecimal.ONE));
    assertThrows(DomainException.class, () -> KpiBaseline.check(KpiBaseline.CALLS, monday, BigDecimal.valueOf(-1)));
    assertThrows(DomainException.class, () -> KpiBaseline.check(KpiBaseline.ADOPTION, monday, BigDecimal.valueOf(101)));
    KpiBaseline.check(KpiBaseline.WRITE_OFFS, monday, new BigDecimal("125000.50"));
  }
}
