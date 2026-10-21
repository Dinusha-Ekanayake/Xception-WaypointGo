package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.domain.Playbooks.Facts;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Situation;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Tally;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Window;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * One prediction for a whole trip (decision suggestions part 2,
 * docs/issues/decision-suggestions/PLAN.md, R-ML-34): from the signals on the
 * trip, the chance of each decision about the trip and, for every stop still
 * waiting, of each outcome of that stop. Advice only: it never acts.
 *
 * <p>A signal is a report, or later a message read automatically, with its
 * situation. A breakdown, a blocked road or running late is about the trip and
 * touches every stop still waiting; store closed, cannot unload and a goods
 * problem are about one stop, the one named, else the stop the van is at, else
 * the next one. A signal about a stop already done or skipped has been decided
 * and adds nothing.
 *
 * <p>Ending the trip sends every stop still waiting to the next day (R-EXE-31),
 * so a stop's chance of going to the next day is the trip's chance of ending
 * plus, if it does not end, the stop's own. The numbers for a trip situation are
 * the team's starting priors (assumption A-46) until @jv-ransika sets them.
 */
public final class TripOutlook {
  /** Sent back with a decision, as {@link Playbooks#VERSION}. */
  public static final int VERSION = 1;

  public enum TripAction { END_TRIP, WAIT, CARRY_ON }

  public enum StopAction { SKIP_NEXT_DAY, SKIP_CANCEL, ASK_RECEIVE_LATER, DELIVER }

  /** Where a signal came from: a report picked by its writer, or a message read automatically. */
  public enum Source { REPORT, MESSAGE }

  /** @param outletId the stop the signal names, when it names one */
  public record Signal(UUID messageId, Situation situation, Optional<String> outletId, Instant at, Source source) {}

  /** @param facts as {@link Playbooks.Facts}, its window projected to the stop's expected arrival */
  public record Stop(UUID deliveryId, String outletId, int sequence, Facts facts) {}

  /** @param noneInWindow every stop still waiting will miss its window, so going on delivers nothing in time today */
  public record Trip(int stopsLeft, boolean noneInWindow) {}

  public record Chance<A extends Enum<A>>(A action, double chance, Optional<String> warning) {}

  /**
   * @param situation the stop's own situation, else the trip's
   * @param learned the stop's own chances came from what dispatchers chose, not the rules
   * @param because the signals behind it
   */
  public record StopOutlook(
      UUID deliveryId, String outletId, Situation situation, List<Chance<StopAction>> chances, boolean learned,
      List<UUID> because) {}

  public record Outlook(
      Optional<Situation> tripSituation, List<Chance<TripAction>> trip, List<UUID> tripBecause, List<StopOutlook> stops) {
    public static final Outlook NONE = new Outlook(Optional.empty(), List.of(), List.of(), List.of());
  }

  /** The situations about the trip as a whole, most serious first. */
  static final List<Situation> TRIP_WIDE = List.of(Situation.BREAKDOWN, Situation.ROAD_BLOCKED, Situation.RUNNING_LATE);

  private static final Set<Situation> ABOUT_A_STOP =
      Set.of(Situation.STORE_CLOSED, Situation.CANNOT_UNLOAD, Situation.GOODS_PROBLEM);

  private static final Map<Situation, Map<TripAction, Double>> TRIP_PRIORS = new EnumMap<>(Situation.class);

  /** A trip situation's effect on one waiting stop, by where its window will stand when the van gets there. */
  private static final Map<Window, Map<StopAction, Double>> STOP_PRIORS = new EnumMap<>(Window.class);

  static {
    TRIP_PRIORS.put(Situation.BREAKDOWN, priors(TripAction.END_TRIP, 0.5, TripAction.WAIT, 0.4, TripAction.CARRY_ON, 0.1));
    TRIP_PRIORS.put(Situation.ROAD_BLOCKED, priors(TripAction.CARRY_ON, 0.5, TripAction.WAIT, 0.3, TripAction.END_TRIP, 0.2));
    TRIP_PRIORS.put(Situation.RUNNING_LATE, priors(TripAction.CARRY_ON, 0.85, TripAction.END_TRIP, 0.15));
    STOP_PRIORS.put(Window.MISSED, priors(StopAction.SKIP_NEXT_DAY, 0.6, StopAction.DELIVER, 0.25, StopAction.ASK_RECEIVE_LATER, 0.15));
    STOP_PRIORS.put(Window.CLOSING, priors(StopAction.DELIVER, 0.5, StopAction.SKIP_NEXT_DAY, 0.3, StopAction.ASK_RECEIVE_LATER, 0.2));
    STOP_PRIORS.put(Window.INSIDE, priors(StopAction.DELIVER, 0.85, StopAction.SKIP_NEXT_DAY, 0.1, StopAction.ASK_RECEIVE_LATER, 0.05));
    STOP_PRIORS.put(Window.UNKNOWN, priors(StopAction.DELIVER, 0.8, StopAction.SKIP_NEXT_DAY, 0.15, StopAction.ASK_RECEIVE_LATER, 0.05));
  }

  private TripOutlook() {}

  /**
   * @param stops the trip's stops, done or not
   * @param tallies what dispatchers chose before, by situation (part 1's log)
   */
  public static Outlook of(List<Signal> signals, Trip trip, List<Stop> stops, Function<Situation, Tally> tallies) {
    List<Stop> open = stops.stream().filter(s -> s.facts().stopOpen()).sorted(Comparator.comparingInt(Stop::sequence)).toList();
    if (open.isEmpty() || trip.stopsLeft() == 0) {
      return Outlook.NONE;
    }
    List<Signal> wide = signals.stream().filter(s -> TRIP_WIDE.contains(s.situation())).toList();
    Optional<Situation> tripSituation =
        TRIP_WIDE.stream().filter(t -> wide.stream().anyMatch(s -> s.situation() == t)).findFirst();
    List<Chance<TripAction>> tripChances = tripSituation.map(t -> tripChances(t, trip)).orElse(List.of());
    double ends = tripChances.stream().filter(c -> c.action() == TripAction.END_TRIP).mapToDouble(Chance::chance).sum();
    List<UUID> tripBecause = wide.stream().map(Signal::messageId).toList();

    List<StopOutlook> outlooks = new ArrayList<>();
    for (Stop stop : open) {
      List<Signal> own = signals.stream()
          .filter(s -> ABOUT_A_STOP.contains(s.situation()))
          .filter(s -> stopOf(s, open).map(stop::equals).orElse(false))
          .toList();
      Optional<Situation> ownSituation = own.stream().map(Signal::situation).findFirst();
      if (ownSituation.isEmpty() && tripSituation.isEmpty()) {
        continue;
      }
      boolean learned = ownSituation.map(s -> Playbooks.learned(tallies.apply(s))).orElse(false);
      Map<StopAction, Double> base = new EnumMap<>(StopAction.class);
      Map<StopAction, String> warnings = new EnumMap<>(StopAction.class);
      if (ownSituation.isPresent()) {
        for (Playbooks.Suggestion x : Playbooks.chances(ownSituation.get(), stop.facts(), tallies.apply(ownSituation.get()))) {
          StopAction a = outcome(x.action());
          base.merge(a, x.chance(), Double::sum);
          x.warning().ifPresent(w -> warnings.put(a, w));
        }
      } else {
        base.putAll(tripEffect(stop.facts(), warnings));
      }
      List<UUID> because = new ArrayList<>(own.stream().map(Signal::messageId).toList());
      because.addAll(tripBecause);
      outlooks.add(new StopOutlook(
          stop.deliveryId(), stop.outletId(), ownSituation.or(() -> tripSituation).orElseThrow(),
          withTripEnding(base, ends, warnings), learned, List.copyOf(because)));
    }
    return new Outlook(tripSituation, tripChances, tripBecause, List.copyOf(outlooks));
  }

  /** The stop a signal is about: the one it names, else the stop the van is at, else the next one; empty when decided. */
  static Optional<Stop> stopOf(Signal s, List<Stop> open) {
    if (s.outletId().isPresent()) {
      // A named stop that is no longer open has been decided: the signal adds nothing.
      return open.stream().filter(x -> x.outletId().equals(s.outletId().get())).findFirst();
    }
    return open.stream().filter(x -> x.facts().reached()).findFirst().or(() -> open.stream().findFirst());
  }

  private static List<Chance<TripAction>> tripChances(Situation s, Trip trip) {
    Map<TripAction, Double> p = new EnumMap<>(TRIP_PRIORS.get(s));
    if (trip.noneInWindow()) {
      p.computeIfPresent(TripAction.END_TRIP, (k, v) -> v * 2); // Nothing left can arrive in time: going on gains little today.
    }
    return chancesOf(p, Map.of());
  }

  /** A trip situation's effect on a stop with no word of its own, checked against the stop's rules. */
  private static Map<StopAction, Double> tripEffect(Facts f, Map<StopAction, String> warnings) {
    Map<StopAction, Double> p = new EnumMap<>(STOP_PRIORS.get(f.window()));
    if (f.mall()) {
      p.remove(StopAction.ASK_RECEIVE_LATER); // A mall's window is fixed: it is never asked (R-PLN-14).
      if (f.window() == Window.MISSED) {
        p.remove(StopAction.DELIVER); // Nothing is unloaded at a mall after its window (EXE-20).
      }
    }
    if (f.deferredBefore() && p.containsKey(StopAction.SKIP_NEXT_DAY)) {
      p.computeIfPresent(StopAction.SKIP_NEXT_DAY, (k, v) -> v * 0.5);
      warnings.put(StopAction.SKIP_NEXT_DAY, "Deferred before: this store would be skipped twice in a row");
    }
    if (f.chilled() && f.window() == Window.MISSED && p.containsKey(StopAction.DELIVER)) {
      warnings.put(StopAction.DELIVER, "Chilled goods: a late delivery risks the cold chain");
    }
    return p;
  }

  /** The trip ending sends this stop to the next day; otherwise its own chances hold. */
  private static List<Chance<StopAction>> withTripEnding(Map<StopAction, Double> own, double ends, Map<StopAction, String> warnings) {
    double total = own.values().stream().mapToDouble(Double::doubleValue).sum();
    Map<StopAction, Double> p = new EnumMap<>(StopAction.class);
    own.forEach((a, v) -> p.put(a, (1 - ends) * (total == 0 ? 0 : v / total)));
    if (ends > 0) {
      p.merge(StopAction.SKIP_NEXT_DAY, ends, Double::sum);
    }
    return chancesOf(p, warnings);
  }

  private static StopAction outcome(Playbooks.Action a) {
    return switch (a) {
      case SKIP_NEXT_DAY -> StopAction.SKIP_NEXT_DAY;
      case SKIP_CANCEL -> StopAction.SKIP_CANCEL;
      case ASK_RECEIVE_LATER -> StopAction.ASK_RECEIVE_LATER;
      // Calling, waiting, telling or noting all end with the stop delivered as planned.
      case WAIT, CALL_STORE, TELL_STORE, NOTED, END_TRIP -> StopAction.DELIVER;
    };
  }

  /** Summing to one, rounded to hundredths, most likely first, the earlier action on a tie. */
  private static <A extends Enum<A>> List<Chance<A>> chancesOf(Map<A, Double> p, Map<A, String> warnings) {
    double sum = p.values().stream().mapToDouble(Double::doubleValue).sum();
    return p.entrySet().stream()
        .filter(e -> e.getValue() > 0)
        .sorted(Map.Entry.<A, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
        .map(e -> new Chance<>(e.getKey(), Math.round(e.getValue() / sum * 100) / 100.0, Optional.ofNullable(warnings.get(e.getKey()))))
        .toList();
  }

  private static <A extends Enum<A>> Map<A, Double> priors(Object... pairs) {
    Map<A, Double> p = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      @SuppressWarnings("unchecked")
      A a = (A) pairs[i];
      p.put(a, (Double) pairs[i + 1]);
    }
    return p;
  }
}
