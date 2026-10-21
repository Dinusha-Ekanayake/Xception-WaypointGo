package com.waypoint.dispatch.intelligence.application;

import com.waypoint.dispatch.identity.contract.PersonQuery;
import com.waypoint.dispatch.intelligence.domain.PromptNames;
import com.waypoint.dispatch.shared.util.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Our accounts' names for {@link PromptNames} (R-ML-31), read through Identity's
 * contract and kept for ten minutes, so a new account is masked within ten minutes
 * and a model call never waits on a fresh read. When the read fails the last list
 * stays; with none yet, nothing is sent at all.
 */
@Component
public class KnownNames {
  static final Duration KEEP = Duration.ofMinutes(10);

  private final PersonQuery people;
  private final Clock clock;
  private volatile PromptNames names;
  private volatile Instant readAt = Instant.MIN;

  public KnownNames(PersonQuery people, Clock clock) {
    this.people = people;
    this.clock = clock;
  }

  /** The next {@link #current()} reads the names again; for a test that has just made an account. */
  void forget() {
    readAt = Instant.MIN;
  }

  /** The current names, or empty when they have never been read: the caller then sends nothing. */
  public java.util.Optional<PromptNames> current() {
    Instant now = clock.now();
    if (names == null || readAt.plus(KEEP).isBefore(now)) {
      try {
        names = PromptNames.of(people.activeNames());
        readAt = now;
      } catch (RuntimeException e) {
        // Keep the last list: an older list masks nearly everyone; no list masks no one.
      }
    }
    return java.util.Optional.ofNullable(names);
  }
}
