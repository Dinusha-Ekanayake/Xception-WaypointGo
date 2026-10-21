package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.shared.error.DomainException;
import com.waypoint.dispatch.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What to do about a situation on the road (issue #269, docs/issues/decision-suggestions/PLAN.md):
 * the steps, a message ready to send, and the decisions the dispatcher may take,
 * each with how likely it is to be the one taken. One definition (rule 5) for
 * Live's "Needs you" and the trip's chat. Pure: no clock, no database.
 *
 * <p>The situation comes from facts, never from free text. The decisions are
 * a short list per situation, checked against rules that already exist: a stop
 * done takes no skip, a mall is never asked to receive late and never waited
 * at past its window, a store deferred before is warned of a second skip. The
 * chances are rule priors agreed with @jv-ransika until there are enough
 * decided cases to count (PLAN, "How it works", 3). Suggestions are advice:
 * nothing here acts.
 */
public final class Playbooks {
  /** The catalogue's version, kept with each suggestion shown so the log knows which rules made it. */
  public static final int VERSION = 1;
  /** At most this many chips beside a report. */
  public static final int SHOWN = 3;
  /** A window closing within this is "closing". */
  public static final Duration CLOSING_WITHIN = Duration.ofMinutes(30);

  private Playbooks() {}

  public enum Situation {
    // Live's "Needs you" (#269 first slice).
    WINDOW, OFFLINE, FAILED, LEFT, ISSUE,
    // A report on the trip's thread.
    STORE_CLOSED, CANNOT_UNLOAD, BREAKDOWN, ROAD_BLOCKED, RUNNING_LATE, GOODS_PROBLEM;

    public static Situation parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "unknown situation " + value);
      }
    }
  }

  public enum Action {
    SKIP_NEXT_DAY, SKIP_CANCEL, ASK_RECEIVE_LATER, WAIT, CALL_STORE, END_TRIP, TELL_STORE, NOTED;

    public static Action parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new DomainException(ErrorCode.VALIDATION_FAILED, "unknown decision " + value);
      }
    }
  }

  public enum Window {
    INSIDE, CLOSING, MISSED, UNKNOWN;

    /** Where the window stands at {@code now}; unknown without a close. */
    public static Window at(Optional<Instant> closesAt, Instant now) {
      if (closesAt.isEmpty()) {
        return UNKNOWN;
      }
      if (now.isAfter(closesAt.get())) {
        return MISSED;
      }
      return Duration.between(now, closesAt.get()).compareTo(CLOSING_WITHIN) <= 0 ? CLOSING : INSIDE;
    }
  }

  /**
   * @param stopOpen the stop is pending or reached, not yet done or skipped
   * @param reached the van is at the stop
   * @param deferredBefore the order was deferred on an earlier run, so a skip now is its second
   * @param stopsLeft the trip's stops not yet done, this one included
   */
  public record Facts(
      boolean stopOpen, boolean reached, Window window, boolean mall, boolean chilled, boolean deferredBefore,
      int stopsLeft) {}

  public record Message(String to, String template) {}

  public record Playbook(Situation situation, List<String> steps, Optional<Message> message) {}

  /** @param chance the share of the time this decision is expected, 0 to 1, summing to 1 over what is offered */
  public record Suggestion(Action action, double chance, Optional<String> warning) {}

  // ---- the catalogue --------------------------------------------------------

  private static final Map<Situation, Playbook> PLAYBOOKS = new EnumMap<>(Situation.class);
  private static final Map<Situation, Map<Action, Double>> PRIORS = new EnumMap<>(Situation.class);

  private static void book(Situation s, Message message, String... steps) {
    PLAYBOOKS.put(s, new Playbook(s, List.of(steps), Optional.ofNullable(message)));
  }

  private static void priors(Situation s, Object... pairs) {
    Map<Action, Double> p = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      p.put((Action) pairs[i], (Double) pairs[i + 1]);
    }
    PRIORS.put(s, p);
  }

  static {
    // The first five are @Dinusha-Ekanayake's playbooks (frontend dispatcher/data/playbooks.ts), moved here unchanged.
    book(Situation.WINDOW,
        new Message("outlet", "{vehicle} is running late to {store} and is now expected at {expected}. Your window closes at {window close}. Can you still receive it?"),
        "Tell the store the new arrival time, so it can keep someone at the dock.",
        "Open the trip and check the stops after this one: a late stop can make the next one late too.",
        "If the store cannot receive after its window, record the delivery as not made and book a make-up.");
    book(Situation.OFFLINE,
        new Message("outlet", "We have not heard from {vehicle} for a while, so its arrival at {store} is not confirmed. We will tell you as soon as we know."),
        "Check the vehicle's last position on the map and when it was seen.",
        "Write to the driver on the trip's messages: it is delivered when the phone has a signal again.",
        "Tell the next store its arrival time is not confirmed.");
    book(Situation.FAILED,
        new Message("outlet", "The delivery to {store} on {vehicle} could not be completed today. We are arranging it for the next run and will confirm the day."),
        "Open the trip and read why the driver could not deliver.",
        "Tell the store what happened and that a new day will be confirmed.",
        "Book a make-up delivery from the issue, so the order is offered first on the next plan.");
    book(Situation.LEFT,
        new Message("outlet", "{vehicle} left {store} before your report was in. Please check what arrived and confirm the receipt."),
        "Open the trip and check whether the store has answered since the driver left.",
        "Ask the store to check what arrived and confirm the receipt; if the driver disagreed, open the issue.");
    book(Situation.ISSUE, null,
        "Open the issue and read what was reported.",
        "Book the make-up delivery, or resolve the issue with a reason.");

    // Reports on the trip's thread (R-EXE-31 decisions).
    book(Situation.STORE_CLOSED,
        new Message("outlet", "{vehicle} is at {store} and nobody is there to receive. Can someone come to the door?"),
        "Call the store: someone may be on the way.",
        "If nobody comes, skip the store to the next day, or cancel if the order is no longer wanted.");
    book(Situation.CANNOT_UNLOAD,
        new Message("outlet", "{vehicle} cannot unload at {store} right now. Can the dock be cleared, or is there another way in?"),
        "Call the store about the dock or the way in.",
        "If it cannot be cleared soon, skip the store to the next day.");
    book(Situation.BREAKDOWN,
        new Message("outlet", "{vehicle} has broken down on the way to {store}. We will tell you as soon as we know when it can come."),
        "Find out from the driver how long the repair takes.",
        "If it cannot go on today, end the trip: every stop not done goes first on the next day.");
    book(Situation.ROAD_BLOCKED,
        new Message("outlet", "{vehicle} is held up by a blocked road on the way to {store}. It is now expected at {expected}."),
        "Tell the stores still waiting the new time.",
        "If the way stays closed, end the trip and send the stops to the next day.");
    book(Situation.RUNNING_LATE,
        new Message("outlet", "{vehicle} is running late to {store} and is now expected at {expected}. Your window closes at {window close}. Can you still receive it?"),
        "Tell the store the new time.",
        "Past the window, ask the store whether it can still receive, or skip it to the next day.");
    book(Situation.GOODS_PROBLEM, null,
        "Leave it for the day's end: it is in the Issues list to resend, write off or close with no fault.");

    priors(Situation.WINDOW, Action.TELL_STORE, 0.6, Action.ASK_RECEIVE_LATER, 0.3, Action.SKIP_NEXT_DAY, 0.1);
    priors(Situation.STORE_CLOSED, Action.CALL_STORE, 0.35, Action.SKIP_NEXT_DAY, 0.3, Action.WAIT, 0.2,
        Action.ASK_RECEIVE_LATER, 0.1, Action.SKIP_CANCEL, 0.05);
    priors(Situation.CANNOT_UNLOAD, Action.CALL_STORE, 0.4, Action.SKIP_NEXT_DAY, 0.3, Action.WAIT, 0.2,
        Action.SKIP_CANCEL, 0.1);
    priors(Situation.BREAKDOWN, Action.END_TRIP, 0.45, Action.WAIT, 0.35, Action.TELL_STORE, 0.2);
    priors(Situation.ROAD_BLOCKED, Action.TELL_STORE, 0.5, Action.WAIT, 0.3, Action.END_TRIP, 0.2);
    priors(Situation.RUNNING_LATE, Action.TELL_STORE, 0.6, Action.ASK_RECEIVE_LATER, 0.3, Action.NOTED, 0.1);
    priors(Situation.GOODS_PROBLEM, Action.NOTED, 0.7, Action.CALL_STORE, 0.3);
  }

  public static Playbook playbook(Situation s) {
    return PLAYBOOKS.get(s);
  }

  public static List<Playbook> all() {
    return List.copyOf(PLAYBOOKS.values());
  }

  /** The situation a report on the trip's thread names (messaging report types); empty when none fits. */
  public static Optional<Situation> ofReport(String reportType) {
    return Optional.ofNullable(
        switch (reportType == null ? "" : reportType) {
          case "failed_delivery" -> Situation.STORE_CLOSED;
          case "vehicle_fault" -> Situation.BREAKDOWN;
          case "road_disruption" -> Situation.ROAD_BLOCKED;
          case "late" -> Situation.RUNNING_LATE;
          case "damaged_goods", "receipt_dispute", "stock_discrepancy", "loading_shortfall" -> Situation.GOODS_PROBLEM;
          default -> null;
        });
  }

  /**
   * The decisions to offer, most likely first, at most {@link #SHOWN}: the
   * situation's priors less what the rules rule out, the warnings attached, and
   * the chances made to sum to one over what is left.
   */
  public static List<Suggestion> suggest(Situation s, Facts f) {
    Map<Action, Double> priors = PRIORS.getOrDefault(s, Map.of());
    List<Suggestion> allowed = new ArrayList<>();
    for (Map.Entry<Action, Double> e : priors.entrySet()) {
      Action a = e.getKey();
      double chance = e.getValue();
      Optional<String> warning = Optional.empty();
      boolean onTheStop = a == Action.SKIP_NEXT_DAY || a == Action.SKIP_CANCEL || a == Action.ASK_RECEIVE_LATER
          || a == Action.WAIT;
      if (onTheStop && !f.stopOpen()) {
        continue; // The stop is done or already off the trip.
      }
      if (a == Action.ASK_RECEIVE_LATER && f.mall()) {
        continue; // A mall's window is fixed: it is never asked (R-PLN-14).
      }
      if (a == Action.WAIT && f.mall() && f.window() == Window.MISSED) {
        continue; // Nothing is unloaded at a mall after its window (EXE-20).
      }
      if (a == Action.END_TRIP && f.stopsLeft() == 0) {
        continue;
      }
      if (a == Action.WAIT && f.chilled() && f.window() == Window.MISSED) {
        chance *= 0.5;
        warning = Optional.of("Chilled goods: waiting longer risks the cold chain");
      }
      if (a == Action.SKIP_NEXT_DAY && f.deferredBefore()) {
        chance *= 0.5;
        warning = Optional.of("Deferred before: this store would be skipped twice in a row");
      }
      if (a == Action.SKIP_NEXT_DAY && f.window() == Window.MISSED) {
        chance *= 1.5; // Past the window, the day is lost for this store.
      }
      allowed.add(new Suggestion(a, chance, warning));
    }
    allowed.sort(Comparator.comparingDouble(Suggestion::chance).reversed());
    List<Suggestion> top = allowed.subList(0, Math.min(SHOWN, allowed.size()));
    double sum = top.stream().mapToDouble(Suggestion::chance).sum();
    return top.stream()
        .map(x -> new Suggestion(x.action(), sum == 0 ? 0 : Math.round(x.chance() / sum * 100) / 100.0, x.warning()))
        .toList();
  }
}
