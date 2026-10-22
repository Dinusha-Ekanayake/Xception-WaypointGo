package com.waypoint.dispatch.referencedata.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** R-CAL-05: generated days carry paydays exactly as the supplied calendar does. */
class OperatingCalendarPolicyTest {
  @Test
  void theRuleGivesEveryPaydayOfTheSuppliedCalendarAndNoOther() throws Exception {
    List<String> rows = Files.readAllLines(Path.of("../data/General Data/calendar.csv"), StandardCharsets.UTF_8);
    int paydays = 0;
    for (String row : rows.subList(1, rows.size())) {
      String[] f = row.split(",", -1);
      LocalDate day = LocalDate.parse(f[0]);
      boolean supplied = "1".equals(f[6]);
      paydays += supplied ? 1 : 0;
      assertEquals(supplied, OperatingCalendarPolicy.isPayday(day), day.toString());
    }
    assertTrue(paydays > 50, "the supplied calendar has its paydays: " + paydays);
  }

  @Test
  void generatedDaysPastTheSuppliedCalendarHavePaydays() {
    long paydays = OperatingCalendarPolicy.extendFrom(LocalDate.of(2026, 6, 28), LocalDate.of(2026, 12, 31)).stream()
        .filter(CalendarDay::payday).count();
    assertEquals(13, paydays, "30 June, then two a month to December");
    assertTrue(OperatingCalendarPolicy.isPayday(LocalDate.of(2026, 10, 24)), "the 25th is a Sunday: the Saturday before");
    assertTrue(OperatingCalendarPolicy.isPayday(LocalDate.of(2026, 10, 31)));
  }
}
