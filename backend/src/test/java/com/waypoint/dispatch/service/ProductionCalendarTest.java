package com.waypoint.dispatch.service;
import static org.junit.jupiter.api.Assertions.*;
import com.waypoint.dispatch.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
class ProductionCalendarTest {
  @Test void productionSupportsCurrentDatesWithoutChangingHistoricalData() {
    var loader = new ReferenceLoader("../data");
    ReflectionTestUtils.setField(loader, "demoMode", "0"); loader.init();
    var monday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Colombo"))
        .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
    assertTrue(Planning.operating(monday.toString(), loader.get()));
    assertFalse(Planning.operating(monday.minusDays(1).toString(), loader.get()));
  }
}
