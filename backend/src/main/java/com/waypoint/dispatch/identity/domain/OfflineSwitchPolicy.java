package com.waypoint.dispatch.identity.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which operator switches made offline the server adds to a device's operator
 * history when it reconnects (decision 2026-10-01, R-IAM-20).
 *
 * <p>The device checked the PIN against the crew list it downloaded; the server
 * cannot recheck it. So it accepts a switch only to someone on that device's
 * crew, within the crew list's lifetime, after the history it already has, and
 * in order. Each one is audited as an offline switch.
 */
public final class OfflineSwitchPolicy {
  /** How long a downloaded crew list may be used offline. */
  public static final Duration CREW_LIST_LIFETIME = Duration.ofHours(12);
  /** Device clocks drift; a switch this far ahead of the server is still accepted. */
  public static final Duration CLOCK_SKEW = Duration.ofMinutes(2);
  public static final int MAX_SWITCHES = 200;

  /** A switch to a crew member, or a lock when {@code userId} is empty. */
  public record Switch(Optional<UUID> userId, Instant at) {}

  private OfflineSwitchPolicy() {}

  /** @param lastServerStart when the latest operator interval the server already has began */
  public static void requireReplayable(
      List<Switch> switches, Set<UUID> crew, Optional<Instant> lastServerStart, Instant now) {
    if (switches.isEmpty() || switches.size() > MAX_SWITCHES) {
      throw refused("Send between 1 and " + MAX_SWITCHES + " offline switches.");
    }
    Instant previous = lastServerStart.orElse(Instant.MIN);
    for (Switch s : switches) {
      if (s.userId().isPresent() && !crew.contains(s.userId().get())) {
        throw refused("An offline switch names someone who is not on this device's crew.");
      }
      if (!s.at().isAfter(previous)) {
        throw refused("Offline switches must come after the device's recorded history, in order.");
      }
      if (s.at().isBefore(now.minus(CREW_LIST_LIFETIME)) || s.at().isAfter(now.plus(CLOCK_SKEW))) {
        throw refused("An offline switch is older than the crew list allows, or in the future.");
      }
      previous = s.at();
    }
  }

  private static DomainException refused(String message) {
    return new DomainException(ErrorCode.CONFLICT, message, List.of("R-IAM-20"));
  }
}
