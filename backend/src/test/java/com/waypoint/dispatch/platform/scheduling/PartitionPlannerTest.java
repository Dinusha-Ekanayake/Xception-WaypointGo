package com.waypoint.dispatch.platform.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartitionPlannerTest {
  private static final Instant MID_JUNE_2027 = Instant.parse("2027-06-15T10:00:00Z");

  @Test
  void theCurrentMonthAndEachMonthAheadAreRequiredAcrossAYearEnd() {
    assertEquals(
        List.of(
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 12, 1),
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 2, 1)),
        PartitionPlanner.required(Instant.parse("2026-11-30T23:59:59Z"), 3));
  }

  @Test
  void aMonthIsCutInUtcNotInTheDepotZone() {
    // 2027-06-30 20:00 UTC is already 1 July in Colombo (UTC+5:30), but the
    // partition bounds are UTC, so the planner must still say June.
    assertEquals(
        LocalDate.of(2027, 6, 1),
        PartitionPlanner.required(Instant.parse("2027-06-30T20:00:00Z"), 0).get(0));
  }

  @Test
  void aPartitionNameYieldsItsMonthAndAnythingElseIsIgnored() {
    assertEquals(LocalDate.of(2027, 3, 1), PartitionPlanner.monthOf("audit_log_2027_03"));
    assertNull(PartitionPlanner.monthOf("audit_log_2027_13"));
    assertNull(PartitionPlanner.monthOf("audit_log_default"));
    assertNull(PartitionPlanner.monthOf("audit_log_2027_03; DROP TABLE x"));
  }

  @Test
  void futureMonthsAreCountedAfterTheCurrentOne() {
    List<String> existing =
        List.of("audit_log_2027_05", "audit_log_2027_06", "audit_log_2027_07", "audit_log_2027_08");

    assertEquals(2, PartitionPlanner.futureCount(MID_JUNE_2027, existing));
    assertEquals(0, PartitionPlanner.futureCount(Instant.parse("2027-08-02T00:00:00Z"), existing));
  }

  @Test
  void aPartitionExpiresOnlyOnceWhollyOutsideTheRetentionWindow() {
    List<String> existing =
        List.of("audit_log_2025_05", "audit_log_2025_06", "audit_log_2025_07", "audit_log_2027_06");

    // 24 months before June 2027 is June 2025: June 2025 itself is still inside.
    assertEquals(
        List.of("audit_log_2025_05"), PartitionPlanner.expired(MID_JUNE_2027, 24, existing));
  }

  @Test
  void zeroRetentionKeepsEverything() {
    assertEquals(
        List.of(), PartitionPlanner.expired(MID_JUNE_2027, 0, List.of("audit_log_2020_01")));
  }
}
