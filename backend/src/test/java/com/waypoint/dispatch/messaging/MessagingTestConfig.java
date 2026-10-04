package com.waypoint.dispatch.messaging;

import com.waypoint.dispatch.shared.util.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock the test moves, so the thread's posting window (R-MSG-04) can be exercised on purpose. */
@TestConfiguration
class MessagingTestConfig {

  @Bean
  @Primary
  MovableClock messagingClock() {
    return new MovableClock();
  }

  static final class MovableClock implements Clock {
    private volatile Instant now;

    @Override
    public Instant now() {
      return now == null ? Instant.now() : now;
    }

    void set(Instant instant) {
      now = instant;
    }

    void reset() {
      now = null;
    }
  }
}
