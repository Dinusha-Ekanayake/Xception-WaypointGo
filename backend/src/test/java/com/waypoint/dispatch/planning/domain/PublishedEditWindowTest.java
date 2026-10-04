package com.waypoint.dispatch.planning.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** R-PLN-43: a published plan takes changes until 16:00 depot time on its service day. */
class PublishedEditWindowTest {
  private static final LocalDate DAY = LocalDate.of(2026, 10, 5);

  @Test
  void theWindowClosesAtFourInTheAfternoonDepotTimeOnTheServiceDay() {
    // 16:00 in Colombo (UTC+05:30) is 10:30 UTC.
    assertEquals(Instant.parse("2026-10-05T10:30:00Z"), PublishedEditWindow.closesAt(DAY));
    assertTrue(PublishedEditWindow.isOpen(DAY, Instant.parse("2026-10-05T10:29:59Z")));
    assertFalse(PublishedEditWindow.isOpen(DAY, Instant.parse("2026-10-05T10:30:00Z")));
  }

  @Test
  void aChangeAfterTheWindowIsRefusedWithTheRule() {
    assertDoesNotThrow(() -> PublishedEditWindow.requireOpen(DAY, Instant.parse("2026-10-04T18:00:00Z")));
    DomainException refused =
        assertThrows(DomainException.class, () -> PublishedEditWindow.requireOpen(DAY, Instant.parse("2026-10-05T11:00:00Z")));
    assertEquals(ErrorCode.CONFLICT, refused.code());
    assertTrue(refused.rules().contains("R-PLN-43"));
  }
}
