package com.waypoint.dispatch.execution.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The expected arrival at the stops still to come (R-EXE-15, roadmap T1-03).
 *
 * <p>Deterministic, by propagation: from the vehicle's last stop, its actual
 * departure (or, while it is still there, the planned minutes at the stop),
 * plus the planned drive to the next stop, then each stop after it the same
 * way, waiting where a window is not open yet. The next stop is never expected
 * before now, so a vehicle held up on the road is seen late without a model.
 * Where the plan carried no minutes at a stop, the planned gap between arrivals
 * is used, which carries the delay forward as before. A trip that is ahead of
 * plan is not announced as early; a store prepares for the planned time.
 */
public final class EtaPolicy {
  /** A delay is announced again only once it has moved this far, so a slow road is one message. */
  public static final int ANNOUNCE_STEP_MINUTES = 10;

  private EtaPolicy() {}

  public static int delayMinutes(Instant arrivedAt, Instant plannedArrival) {
    if (!arrivedAt.isAfter(plannedArrival)) {
      return 0;
    }
    return (int) Math.min(Integer.MAX_VALUE, Duration.between(plannedArrival, arrivedAt).toMinutes());
  }

  public static boolean worthAnnouncing(int delayMinutes, int lastAnnouncedMinutes) {
    return Math.abs(delayMinutes - lastAnnouncedMinutes) >= ANNOUNCE_STEP_MINUTES;
  }

  public static Instant expectedArrival(Instant plannedArrival, int delayMinutes) {
    return plannedArrival.plus(Duration.ofMinutes(delayMinutes));
  }

  /**
   * One stop of a trip as the estimate needs it.
   *
   * @param serviceMinutes the plan's minutes at the stop, when carried
   * @param finished delivered or failed: the vehicle has left it
   */
  public record StopTimes(
      UUID deliveryId,
      Instant plannedArrival,
      Instant windowOpen,
      Optional<Integer> serviceMinutes,
      Optional<Instant> arrivedAt,
      Optional<Instant> leftAt,
      boolean finished) {}

  /**
   * The expected arrival at each stop not reached yet, in stop order. Empty
   * until the vehicle has reached its first stop: before that the plan stands.
   *
   * @param stops the trip's stops in sequence
   */
  public static Map<UUID, Instant> propagate(List<StopTimes> stops, Instant now) {
    Map<UUID, Instant> out = new LinkedHashMap<>();
    StopTimes prev = null;
    Instant prevLeave = null;
    boolean firstAhead = true;
    for (StopTimes s : stops) {
      if (s.finished() && s.arrivedAt().isEmpty() && s.leftAt().isEmpty()) {
        // Skipped or moved off the trip: the vehicle never went there.
        continue;
      }
      if (s.finished() || s.arrivedAt().isPresent()) {
        prev = s;
        prevLeave = leaving(s, now);
        firstAhead = true;
        continue;
      }
      if (prev == null) {
        continue;
      }
      Instant reach = prevLeave.plus(drive(prev, s));
      if (firstAhead) {
        reach = latest(reach, now);
        firstAhead = false;
      }
      Instant expected = latest(reach, s.plannedArrival());
      out.put(s.deliveryId(), expected);
      prev = s;
      prevLeave = latest(expected, s.windowOpen()).plus(service(s));
    }
    return out;
  }

  /** When the vehicle left, or will leave, a stop it has reached. */
  private static Instant leaving(StopTimes s, Instant now) {
    if (s.leftAt().isPresent()) {
      return s.leftAt().get();
    }
    Instant start = latest(s.arrivedAt().orElse(now), s.windowOpen());
    // Still there: with the plan's minutes it leaves when they are up, and not before now.
    return s.serviceMinutes().isPresent() ? latest(start.plus(service(s)), now) : start;
  }

  /** The planned drive between two stops: the next arrival less the planned departure. */
  private static Duration drive(StopTimes from, StopTimes to) {
    Instant plannedLeave = latest(from.plannedArrival(), from.windowOpen()).plus(service(from));
    Duration d = Duration.between(plannedLeave, to.plannedArrival());
    return d.isNegative() ? Duration.ZERO : d;
  }

  private static Duration service(StopTimes s) {
    return Duration.ofMinutes(s.serviceMinutes().orElse(0));
  }

  private static Instant latest(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  /** Whether a stop's estimate moved far enough from what its store was last told to tell it again. */
  public static boolean worthAnnouncing(Instant expected, Instant lastTold) {
    return Duration.between(lastTold, expected).abs().toMinutes() >= ANNOUNCE_STEP_MINUTES;
  }
}
