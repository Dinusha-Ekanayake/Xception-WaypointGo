package com.waypoint.dispatch.issues.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A question the dispatcher asks one store on a live trip, and its answer
 * (roadmap FF-09, R-ISS-12 to R-ISS-14). Pure: the time is a parameter.
 *
 * <ul>
 *   <li>R-ISS-12: {@code RECEIVE_LATE} asks whether someone can receive at about a
 *       time; never a mall, whose window is fixed. {@code DOCK_HELP} asks the next
 *       store whether the crew may unload a stop's goods at its dock to reach its
 *       own, and reload them; never "keep them", so chilled goods stay in the
 *       refrigerated vehicle. Only a stop not yet finished is asked about.
 *   <li>R-ISS-13: the answer is due before the vehicle arrives: at least
 *       {@link #MIN_DEADLINE} from now, at most {@link #MAX_DEADLINE}, and
 *       {@link #BEFORE_ARRIVAL} before the expected arrival when that leaves room.
 *   <li>R-ISS-14: the store answers its own outlet's question; the dispatcher may
 *       record an answer the store gave by phone. One answer; an answer after the
 *       deadline is recorded and marked late. A withdrawn question takes none.
 * </ul>
 */
public final class DecisionRules {
  public static final Duration MIN_DEADLINE = Duration.ofMinutes(5);
  public static final Duration MAX_DEADLINE = Duration.ofMinutes(20);
  public static final Duration BEFORE_ARRIVAL = Duration.ofMinutes(10);
  /** The latest a store is asked to receive: the service day is short, and the vehicle has a second trip. */
  public static final Duration LATEST_RECEIVE = Duration.ofHours(4);
  public static final int REASON_MAX = 500;

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

  private DecisionRules() {}

  public enum Kind {
    RECEIVE_LATE,
    DOCK_HELP;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Kind parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (RuntimeException e) {
        throw invalid("kind is receive_late or dock_help", "R-ISS-12");
      }
    }
  }

  public enum Answer {
    YES,
    NO;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static Answer parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (RuntimeException e) {
        throw invalid("answer is yes or no", "R-ISS-14");
      }
    }
  }

  public enum Via {
    APP,
    PHONE;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public enum State {
    SENT,
    ANSWERED,
    EXPIRED,
    WITHDRAWN;

    public String code() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static State of(String code) {
      return valueOf(code.toUpperCase(Locale.ROOT));
    }
  }

  /** The stop asked about, as the run sheet has it. */
  public record Stop(String outletId, boolean mall, boolean finished, Optional<Instant> expectedArrival) {}

  /** R-ISS-12: checks a RECEIVE_LATE question and returns the time asked about. */
  public static Instant receiveLate(Stop asked, Instant receiveAt, Instant now) {
    open(asked);
    if (asked.mall()) {
      throw invalid(asked.outletId() + " is in a mall: its window is fixed, so a late stop there is redelivered",
          "R-ISS-12");
    }
    if (!receiveAt.isAfter(now)) {
      throw invalid("the time asked about has passed", "R-ISS-12");
    }
    if (receiveAt.isAfter(now.plus(LATEST_RECEIVE))) {
      throw invalid("a store is asked about the next " + LATEST_RECEIVE.toHours() + " hours only", "R-ISS-12");
    }
    return receiveAt;
  }

  /** R-ISS-12: checks a DOCK_HELP question: the helper is a later stop on the same trip, the blocked stop is not done. */
  public static void dockHelp(Stop helper, int helperSequence, Stop blocked, int blockedSequence, boolean sameTrip) {
    open(helper);
    if (!sameTrip || helperSequence <= blockedSequence) {
      throw invalid("the store asked to help is a later stop on the same trip", "R-ISS-12");
    }
    if (blocked.outletId().equals(helper.outletId())) {
      throw invalid("a store is not asked to help with its own goods", "R-ISS-12");
    }
  }

  /** R-ISS-13: when the answer is due. */
  public static Instant deadline(Instant now, Optional<Instant> arrival) {
    Instant earliest = now.plus(MIN_DEADLINE);
    Instant latest = now.plus(MAX_DEADLINE);
    Instant due = arrival.map(a -> a.minus(BEFORE_ARRIVAL)).orElse(latest);
    if (due.isBefore(earliest)) return earliest;
    if (due.isAfter(latest)) return latest;
    return due;
  }

  /** R-ISS-14: whether an answer may be recorded now, and whether it came late. */
  public static boolean answerIsLate(State state, Instant deadline, Instant now) {
    return switch (state) {
      case SENT -> now.isAfter(deadline);
      case EXPIRED -> true;
      case ANSWERED -> throw new DomainException(ErrorCode.CONFLICT, "this question is already answered", List.of("R-ISS-14"));
      case WITHDRAWN -> throw new DomainException(ErrorCode.CONFLICT, "the dispatcher withdrew this question", List.of("R-ISS-14"));
    };
  }

  /** An optional reason: trimmed, at most {@link #REASON_MAX} characters. */
  public static Optional<String> reason(String raw) {
    String r = raw == null ? "" : raw.strip();
    if (r.length() > REASON_MAX) {
      throw invalid("reason is at most " + REASON_MAX + " characters", "R-ISS-14");
    }
    return r.isEmpty() ? Optional.empty() : Optional.of(r);
  }

  /**
   * The question in words, every name and number from the system: what the
   * notification says. The screens draw their own from the same fields.
   */
  public static String question(
      Kind kind, Optional<Instant> receiveAt, Optional<String> aboutOutlet, Optional<Integer> aboutUnits, ZoneId zone) {
    return switch (kind) {
      case RECEIVE_LATE -> "Can someone receive the delivery at about " + clock(receiveAt.orElseThrow(), zone) + "?";
      case DOCK_HELP -> "Goods for " + aboutOutlet.orElseThrow()
          + aboutUnits.map(u -> " (" + u + (u == 1 ? " unit)" : " units)")).orElse("")
          + " are in front of yours. Can the crew unload them at your dock to reach your goods, and reload them?";
    };
  }

  /** What the driver is told of a Yes, every name and time from the system. */
  public static String driverNote(
      Kind kind, String outletId, Optional<Instant> receiveAt, Optional<String> aboutOutlet, ZoneId zone) {
    return switch (kind) {
      case RECEIVE_LATE -> outletId + " will receive at about " + clock(receiveAt.orElseThrow(), zone) + ".";
      case DOCK_HELP -> outletId + " agreed: unload the goods for " + aboutOutlet.orElseThrow()
          + " at their dock to reach theirs, then reload them.";
    };
  }

  public static String clock(Instant at, ZoneId zone) {
    return LocalTime.ofInstant(at, zone).format(CLOCK);
  }

  private static void open(Stop stop) {
    if (stop.finished()) {
      throw new DomainException(ErrorCode.CONFLICT, "the stop at " + stop.outletId() + " is already finished",
          List.of("R-ISS-12"));
    }
  }

  private static DomainException invalid(String message, String rule) {
    return new DomainException(ErrorCode.VALIDATION_FAILED, message, List.of(rule));
  }
}
