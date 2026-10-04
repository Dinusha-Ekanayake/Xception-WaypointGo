package com.waypoint.dispatch.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.identity.domain.PinPolicy;
import com.waypoint.dispatch.shared.error.DomainException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PinPolicyTest {
  private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

  @Test
  void acceptsOnlyFourDigitsAndKeepsLeadingZeroes() {
    PinPolicy.requireWellFormed("0042");
    for (String invalid : List.of("", "123", "12345", "12a4", " 123", "123 ")) {
      assertThrows(DomainException.class, () -> PinPolicy.requireWellFormed(invalid), invalid);
    }
  }

  @Test
  void fiveFailuresStartFiveMinutePauseAndItExpiresExactlyAtTheBoundary() {
    var failures = List.of(NOW.minusSeconds(4), NOW.minusSeconds(3), NOW.minusSeconds(2),
        NOW.minusSeconds(1), NOW);
    assertTrue(PinPolicy.pausedFor(failures.subList(0, 4), NOW).isEmpty());
    assertEquals(java.time.Duration.ofMinutes(5), PinPolicy.pausedFor(failures, NOW).orElseThrow());
    assertTrue(PinPolicy.pausedFor(failures, NOW.plusSeconds(299)).isPresent());
    assertTrue(PinPolicy.pausedFor(failures, NOW.plusSeconds(300)).isEmpty());
    assertFalse(PinPolicy.triesLeft(5) > 0);
  }
}
