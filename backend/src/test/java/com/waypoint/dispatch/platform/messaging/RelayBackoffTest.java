package com.waypoint.dispatch.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RelayBackoffTest {
  private static final Duration BASE = Duration.ofSeconds(2);
  private static final Duration CAP = Duration.ofMinutes(5);

  /** random = 0.5 is the middle of the jitter range, so the nominal value. */
  private static Duration nominal(int attempts) {
    return RelayBackoff.after(attempts, BASE, CAP, 0.5);
  }

  @Test
  void theWaitDoublesFromTheBase() {
    assertEquals(Duration.ofSeconds(2), nominal(1));
    assertEquals(Duration.ofSeconds(4), nominal(2));
    assertEquals(Duration.ofSeconds(8), nominal(3));
    assertEquals(Duration.ofSeconds(64), nominal(6));
  }

  @Test
  void theWaitNeverExceedsTheCap() {
    assertEquals(CAP, nominal(9));
    assertEquals(CAP, nominal(40));
    assertEquals(CAP, nominal(Integer.MAX_VALUE), "a huge attempt count must not overflow into a short wait");
  }

  @Test
  void jitterSpreadsTheWaitAFifthEitherSide() {
    assertEquals(Duration.ofMillis(1600), RelayBackoff.after(1, BASE, CAP, 0.0));
    Duration top = RelayBackoff.after(1, BASE, CAP, 0.999999);
    assertTrue(top.toMillis() > 2390 && top.toMillis() <= 2400, top.toString());
  }

  @Test
  void anAttemptCountBelowOneIsTreatedAsTheFirst() {
    assertEquals(nominal(1), nominal(0));
  }
}
