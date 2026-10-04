package com.waypoint.dispatch.demo;

import static org.junit.jupiter.api.Assertions.*;
import com.waypoint.dispatch.demo.domain.DemoSettings;
import com.waypoint.dispatch.shared.error.DomainException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoDomainTest {
  @Test void disabledSettingsCannotCarryAnOffset() {
    assertThrows(DomainException.class, () -> new DemoSettings(false, 10, 2000, 5000, true, 10));
    assertEquals(0, DemoSettings.defaults().offsetSeconds());
    assertFalse(DemoSettings.defaults().enabled());
  }
  @Test void settingsBoundTimeAndIntervals() {
    assertThrows(DomainException.class, () -> new DemoSettings(true, 604801, 2000, 5000, true, 10));
    assertThrows(DomainException.class, () -> new DemoSettings(true, 0, 1, 5000, true, 10));
    assertThrows(DomainException.class, () -> new DemoSettings(true, 0, 2000, 0, true, 10));
  }
  @Test void aClockTargetBeyondSevenDaysIsRefusedInWords() {
    assertEquals(604800, DemoSettings.offsetTo(604800));
    assertEquals(-3600, DemoSettings.offsetTo(-3600));
    var refused = assertThrows(DomainException.class, () -> DemoSettings.offsetTo(604801));
    assertTrue(refused.getMessage().contains("7 days"));
  }
}
