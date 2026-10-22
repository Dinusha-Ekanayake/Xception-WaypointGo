package com.waypoint.dispatch.intelligence.domain;

import com.waypoint.dispatch.intelligence.domain.Playbooks.Situation;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The trip's problem room (decision suggestions part 3, R-ML-36,
 * docs/issues/decision-suggestions/PLAN.md). Pure. From what was reported, each
 * waiting store's facts and the questions asked of the stores, the state of each
 * store and the one next step for the dispatcher, chosen by these rules in order:
 * ask, end the trip, skip, call, ask the dock, tell the driver about a refused
 * dock, wait, done. Never a guess: every step names the fact it follows from.
 */
public final class ProblemRoom {
  /** How long before a question is due the store gets: as the question itself (R-ISS-13). */
  public static final Duration AT_THE_DOOR = Duration.ofMinutes(15);

  /** The latest a store may be asked to receive (R-ISS-12). */
  public static final Duration FURTHEST = Duration.ofHours(4);

  /** Extra time first offered for each trip situation; the dispatcher may pick another. */
  public static Duration firstExtra(Situation s) {
    return s == Situation.BREAKDOWN ? Duration.ofHours(1) : Duration.ofMinutes(30);
  }

  /** The choices of extra time beside the ask; never more than an hour, past which asking a store to wait helps nobody. */
  public static final List<Duration> EXTRAS = List.of(Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1));

  /** The most extra time a store is asked to wait for. */
  public static final Duration MOST_EXTRA = Duration.ofHours(1);

  static final Set<Situation> TRIP_WIDE = Set.of(Situation.BREAKDOWN, Situation.ROAD_BLOCKED, Situation.RUNNING_LATE);
  static final Set<Situation> AT_A_STOP = Set.of(Situation.STORE_CLOSED, Situation.CANNOT_UNLOAD);

  public enum Outcome { WAITING, REACHED, DELIVERED, SKIPPED, OTHER }

  /**
   * @param outletId the stop the report named, or for a report at the door, the stop the van is at
   */
  public record Problem(Situation situation, Optional<String> outletId, Instant reportedAt) {}

  public record Stop(
      UUID deliveryId, String outletId, int sequence, Outcome outcome, boolean mall, boolean chilled,
      Instant windowClose, Instant expectedArrival, long rowVersion) {}

  /** A question to a store: {@code dock_help} names the stop whose goods are in the way. */
  public record Ask(
      UUID requestId, String kind, UUID deliveryId, Optional<UUID> aboutDeliveryId, Optional<Instant> receiveAt,
      Instant deadline, String state, Optional<String> answer, Instant askedAt) {

    boolean open() {
      return "sent".equals(state);
    }

    boolean said(String a) {
      return answer.filter(a::equals).isPresent();
    }

    boolean expiredUnanswered() {
      return "expired".equals(state) && answer.isEmpty();
    }
  }

  /** What a store's row says. */
  public enum State {
    NOT_ASKED, MALL_CANNOT_WAIT, IN_WINDOW, ASKED, YES, NO, NO_ANSWER, DOCK_ASKED, DOCK_YES, DOCK_NO, SKIPPED, DELIVERED
  }

  public record Row(UUID deliveryId, String outletId, int sequence, State state, Optional<Instant> at, Optional<String> because) {}

  public enum Kind { ASK, END_TRIP, SKIP, CALL, ASK_DOCK, TELL_DRIVER_DOCK, WAIT, DONE }

  /**
   * @param stops the stores the step is about (ASK: each with its time in {@code receiveAt})
   * @param about for ASK_DOCK and TELL_DRIVER_DOCK, the skipped store whose goods are in the way
   * @param why in plain words, the fact it follows from
   * @param endTripToo a breakdown also offers "the driver can't go on: end trip"
   */
  public record Step(
      Kind kind, List<Target> stops, Optional<Target> about, String why, Optional<Duration> extra, boolean endTripToo) {}

  public record Target(UUID deliveryId, String outletId, Optional<Instant> receiveAt, long rowVersion) {}

  public record Room(Situation situation, List<Row> rows, Step next) {}

  /** What a store's chat message was surely read as (R-ML-43), with its masked words. */
  public record Said(UUID messageId, String outletId, String act, String words, Instant at) {}

  /** How a store's words answer its open question. */
  public enum Reads { YES, NO, LATER }

  /** A store's chat words that read as the answer to its open question, for the dispatcher to record (R-ML-45). */
  public record Heard(UUID requestId, String outletId, UUID messageId, String words, Reads reads, Instant at) {}

  /**
   * For each open question, the newest words its store wrote in the chat after it
   * was asked that read as an answer: yes, no, or a later time. Nothing is
   * recorded from them; the dispatcher records it with one tap or says it is not
   * an answer (rule 8: a decision has a person, a reason and a time).
   */
  public static List<Heard> heard(List<Ask> asks, List<Stop> stops, List<Said> said) {
    Map<UUID, String> outletOf = new HashMap<>();
    stops.forEach(s -> outletOf.put(s.deliveryId(), s.outletId()));
    List<Heard> out = new ArrayList<>();
    for (Ask a : asks) {
      if (!a.open() || !outletOf.containsKey(a.deliveryId())) continue;
      String outlet = outletOf.get(a.deliveryId());
      said.stream()
          .filter(m -> m.outletId().equals(outlet) && m.at().isAfter(a.askedAt()))
          .filter(m -> reads(m.act(), a.kind()).isPresent())
          .max(Comparator.comparing(Said::at))
          .ifPresent(m -> out.add(new Heard(a.requestId(), outlet, m.messageId(), m.words(), reads(m.act(), a.kind()).get(), m.at())));
    }
    return out;
  }

  private static Optional<Reads> reads(String act, String kind) {
    return switch (act) {
      case "SAYS_YES", "STORE_OPEN_NOW", "DOCK_CLEAR" -> Optional.of(Reads.YES);
      case "SAYS_NO", "DELIVER_NEXT_DAY" -> Optional.of(Reads.NO);
      // A later time answers a question about receiving late, not one about a dock.
      case "CAN_RECEIVE_LATER" -> "receive_late".equals(kind) ? Optional.of(Reads.LATER) : Optional.empty();
      default -> Optional.empty();
    };
  }

  private ProblemRoom() {}

  /**
   * @param extra the extra time the dispatcher picked for a trip-wide problem; empty takes {@link #firstExtra}
   */
  public static Room of(Problem p, List<Stop> stops, List<Ask> allAsks, Instant now, Optional<Duration> extra) {
    List<Stop> trip = stops.stream().sorted(Comparator.comparingInt(Stop::sequence)).toList();
    // A question asked before this problem was reported answered an earlier one: it decides nothing here.
    List<Ask> asks = allAsks.stream().filter(a -> !a.askedAt().isBefore(p.reportedAt())).toList();
    Duration picked = extra.orElse(firstExtra(p.situation()));
    Duration added = picked.compareTo(MOST_EXTRA) > 0 ? MOST_EXTRA : picked;
    List<Row> rows = new ArrayList<>();
    Optional<Stop> heading = headingTo(p, trip, asks);
    for (Stop s : trip) {
      rows.add(row(p, s, trip, asks, added, heading));
    }
    Step next = next(p, trip, rows, asks, now, added, heading);
    // A breakdown keeps its way out beside every step but ending the trip itself.
    boolean waiting = rows.stream().anyMatch(r -> r.state() != State.DELIVERED && r.state() != State.SKIPPED);
    if (p.situation() == Situation.BREAKDOWN && waiting && next.kind() != Kind.END_TRIP) {
      next = new Step(next.kind(), next.stops(), next.about(), next.why(), next.extra(), true);
    }
    return new Room(p.situation(), List.copyOf(rows), next);
  }

  private static Row row(Problem p, Stop s, List<Stop> trip, List<Ask> asks, Duration extra, Optional<Stop> heading) {
    if (s.outcome() == Outcome.DELIVERED) return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.DELIVERED, Optional.empty(), Optional.empty());
    if (s.outcome() == Outcome.SKIPPED) return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.SKIPPED, Optional.empty(), Optional.empty());
    if (s.outcome() == Outcome.OTHER) return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.DELIVERED, Optional.empty(), Optional.empty());
    Optional<Ask> dock = latest(asks, s.deliveryId(), "dock_help");
    if (dock.isPresent()) {
      Ask d = dock.get();
      State st = d.said("yes") ? State.DOCK_YES : d.said("no") ? State.DOCK_NO : d.expiredUnanswered() ? State.NO_ANSWER : State.DOCK_ASKED;
      return new Row(s.deliveryId(), s.outletId(), s.sequence(), st, Optional.of(d.deadline()), Optional.empty());
    }
    Optional<Ask> late = latest(asks, s.deliveryId(), "receive_late");
    if (late.isPresent()) {
      Ask a = late.get();
      if (a.said("yes")) return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.YES, a.receiveAt(), Optional.empty());
      if (a.said("no")) return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.NO, a.receiveAt(), Optional.empty());
      if (a.expiredUnanswered()) return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.NO_ANSWER, Optional.of(a.deadline()), Optional.empty());
      if (a.open()) return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.ASKED, Optional.of(a.deadline()), Optional.empty());
    }
    if (!concerned(p, s, trip, extra) && !(STUCK.contains(p.situation()) && heading.filter(h -> h.deliveryId().equals(s.deliveryId())).isPresent())) {
      return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.IN_WINDOW, Optional.of(s.expectedArrival()), Optional.empty());
    }
    // A mall's fixed window matters only once it is missed; inside it, it is asked like any store.
    if (s.mall() && !AT_A_STOP.contains(p.situation()) && concerned(p, s, trip, extra)) {
      return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.MALL_CANNOT_WAIT, Optional.empty(), Optional.of("A mall's window is fixed"));
    }
    return new Row(s.deliveryId(), s.outletId(), s.sequence(), State.NOT_ASKED, Optional.empty(), Optional.empty());
  }

  /**
   * Whether the problem touches this waiting store: at the door, the store the van
   * is at; on the road, a store whose window will be missed, once the extra time is
   * added for a breakdown or a blocked road. A store still inside its window is not
   * asked: its own screen already shows the new time (R-EXE-15).
   */
  static boolean concerned(Problem p, Stop s, List<Stop> trip, Duration extra) {
    if (AT_A_STOP.contains(p.situation())) {
      return s.outletId().equals(atTheDoor(p, trip).map(Stop::outletId).orElse(""));
    }
    return TRIP_WIDE.contains(p.situation()) && arrival(p, s, extra).isAfter(s.windowClose());
  }

  /** The van is stuck: the store it is heading to waits for it, whatever its window, and is asked. */
  static final Set<Situation> STUCK = Set.of(Situation.BREAKDOWN, Situation.ROAD_BLOCKED);

  /**
   * The store the van is heading to: the first still waiting that has not said
   * it cannot receive. When that store says No, the next one becomes the store
   * the van is heading to, so it is asked at once.
   */
  static Optional<Stop> headingTo(Problem p, List<Stop> trip, List<Ask> asks) {
    return trip.stream()
        .filter(s -> s.outcome() == Outcome.WAITING || s.outcome() == Outcome.REACHED)
        .filter(s -> !latest(asks, s.deliveryId(), "receive_late").map(a -> a.said("no")).orElse(false))
        .findFirst();
  }

  /** When the van will now reach a store: its expected arrival, plus the extra time on a breakdown or blocked road. */
  static Instant arrival(Problem p, Stop s, Duration extra) {
    return p.situation() == Situation.RUNNING_LATE ? s.expectedArrival() : s.expectedArrival().plus(extra);
  }

  /** A report at the door names its store; else the store the van is at, else the next one. */
  static Optional<Stop> atTheDoor(Problem p, List<Stop> trip) {
    List<Stop> waiting = trip.stream().filter(s -> s.outcome() == Outcome.WAITING || s.outcome() == Outcome.REACHED).toList();
    if (p.outletId().isPresent()) {
      return waiting.stream().filter(s -> s.outletId().equals(p.outletId().get())).findFirst();
    }
    return waiting.stream().filter(s -> s.outcome() == Outcome.REACHED).findFirst().or(() -> waiting.stream().findFirst());
  }

  private static Step next(Problem p, List<Stop> trip, List<Row> rows, List<Ask> asks, Instant now, Duration extra,
      Optional<Stop> heading) {
    boolean breakdown = p.situation() == Situation.BREAKDOWN;
    List<Row> waiting = rows.stream().filter(r -> r.state() != State.DELIVERED && r.state() != State.SKIPPED).toList();
    if (waiting.isEmpty()) {
      return step(Kind.DONE, List.of(), "Every store is settled.");
    }
    // 1. Ask the stores the problem touches that were not asked yet (never a mall past its window).
    List<Target> toAsk = new ArrayList<>();
    for (Row r : waiting) {
      if (r.state() != State.NOT_ASKED) continue;
      Stop s = stop(trip, r.deliveryId());
      Instant at = AT_A_STOP.contains(p.situation()) ? now.plus(AT_THE_DOOR) : arrival(p, s, extra);
      toAsk.add(new Target(s.deliveryId(), s.outletId(), Optional.of(roundUp(at, now)), s.rowVersion()));
    }
    if (!toAsk.isEmpty()) {
      boolean onlyNext = toAsk.size() == 1 && heading.filter(h -> h.deliveryId().equals(toAsk.get(0).deliveryId())).isPresent();
      String why = AT_A_STOP.contains(p.situation())
          ? toAsk.get(0).outletId() + " is the store the driver is at."
          : STUCK.contains(p.situation()) && onlyNext
              ? toAsk.get(0).outletId() + " is next: the van now reaches it about " + toAsk.get(0).receiveAt().map(Wording::hhmm).orElse("later") + "."
          : p.situation() == Situation.RUNNING_LATE
              ? (toAsk.size() == 1 ? "This store's" : "These stores'") + " window will be missed."
              : "Nobody can choose a store's delivery before it says when it can receive.";
      return new Step(Kind.ASK, List.copyOf(toAsk), Optional.empty(), why,
          TRIP_WIDE.contains(p.situation()) && p.situation() != Situation.RUNNING_LATE ? Optional.of(extra) : Optional.empty(),
          breakdown);
    }
    // 2. End the trip when nothing waiting can still be delivered today.
    // A store still in its window or one that said Yes keeps the trip going; one with no answer is called first.
    boolean nothingLeft = waiting.stream().allMatch(r -> r.state() == State.NO || r.state() == State.MALL_CANNOT_WAIT);
    if (nothingLeft && waiting.size() > 1) {
      return step(Kind.END_TRIP, List.of(), "No store still waiting can receive today.");
    }
    // 3. Skip a store that said No, or a mall past its window.
    for (Row r : waiting) {
      if (r.state() == State.NO || r.state() == State.MALL_CANNOT_WAIT) {
        Stop s = stop(trip, r.deliveryId());
        String why = r.state() == State.MALL_CANNOT_WAIT
            ? "A mall's window is fixed: it cannot receive late."
            : s.outletId() + " can't receive" + r.at().map(t -> " at " + Wording.hhmm(t)).orElse("") + ".";
        return step(Kind.SKIP, List.of(target(s, Optional.empty())), why);
      }
    }
    // 4. Call a store whose question expired with no answer.
    for (Row r : waiting) {
      if (r.state() == State.NO_ANSWER) {
        Stop s = stop(trip, r.deliveryId());
        return step(Kind.CALL, List.of(target(s, Optional.empty())), s.outletId() + " did not answer by the deadline.");
      }
    }
    // 5. A skipped store's goods are in front of the next waiting store's: ask that store's dock.
    Optional<Stop> skipped = trip.stream().filter(s -> s.outcome() == Outcome.SKIPPED).findFirst();
    if (skipped.isPresent()) {
      // Only the stop straight after it: once that one is done, the crew re-stowed the goods there.
      Optional<Stop> nextStop = trip.stream()
          .filter(s -> s.sequence() > skipped.get().sequence() && s.outcome() != Outcome.SKIPPED)
          .findFirst()
          .filter(s -> s.outcome() == Outcome.WAITING || s.outcome() == Outcome.REACHED);
      if (nextStop.isPresent()) {
        Optional<Ask> dock = latest(asks, nextStop.get().deliveryId(), "dock_help");
        if (dock.isEmpty()) {
          return new Step(Kind.ASK_DOCK, List.of(target(nextStop.get(), Optional.empty())), Optional.of(target(skipped.get(), Optional.empty())),
              skipped.get().outletId() + "'s goods were loaded in front of " + nextStop.get().outletId() + "'s.", Optional.empty(), false);
        }
        // 6. The dock refused: the driver moves the goods inside the van at the kerb, or says they cannot.
        if (dock.get().said("no")) {
          return new Step(Kind.TELL_DRIVER_DOCK, List.of(target(nextStop.get(), Optional.empty())), Optional.of(target(skipped.get(), Optional.empty())),
              nextStop.get().outletId() + " can't host the unloading.", Optional.empty(), false);
        }
      }
    }
    // 7. Waiting for answers, or done.
    Optional<Row> asked = waiting.stream().filter(r -> r.state() == State.ASKED || r.state() == State.DOCK_ASKED)
        .min(Comparator.comparing(r -> r.at().orElse(Instant.MAX)));
    if (asked.isPresent()) {
      long open = waiting.stream().filter(r -> r.state() == State.ASKED || r.state() == State.DOCK_ASKED).count();
      return step(Kind.WAIT, List.of(),
          (open == 1 ? "1 answer" : open + " answers") + " due by " + asked.get().at().map(Wording::hhmm).orElse("the deadline") + ".");
    }
    return step(Kind.DONE, List.of(), rows.stream().anyMatch(r -> r.state() == State.IN_WINDOW) && waiting.stream().allMatch(r -> r.state() == State.IN_WINDOW || r.state() == State.YES)
        ? "Every store still waiting can receive in time; their screens show the new time."
        : "Every store the problem touches has an answer.");
  }

  private static Step step(Kind kind, List<Target> stops, String why) {
    return new Step(kind, stops, Optional.empty(), why, Optional.empty(), false);
  }

  private static Target target(Stop s, Optional<Instant> at) {
    return new Target(s.deliveryId(), s.outletId(), at, s.rowVersion());
  }

  private static Stop stop(List<Stop> trip, UUID deliveryId) {
    return trip.stream().filter(s -> s.deliveryId().equals(deliveryId)).findFirst().orElseThrow();
  }

  private static Optional<Ask> latest(List<Ask> asks, UUID deliveryId, String kind) {
    return asks.stream()
        .filter(a -> a.deliveryId().equals(deliveryId) && kind.equals(a.kind()) && !"withdrawn".equals(a.state()))
        .max(Comparator.comparing(Ask::askedAt));
  }

  /** Up to the next 5 minutes, at least 5 minutes from now and at most {@link #FURTHEST} (R-ISS-12, R-ISS-15). */
  static Instant roundUp(Instant at, Instant now) {
    Instant earliest = now.plus(Duration.ofMinutes(5));
    Instant t = at.isBefore(earliest) ? earliest : at;
    Instant latest = now.plus(FURTHEST);
    if (t.isAfter(latest)) t = latest;
    long minutes = t.truncatedTo(ChronoUnit.MINUTES).getEpochSecond() / 60;
    long rounded = ((minutes + 4) / 5) * 5;
    Instant out = Instant.ofEpochSecond(rounded * 60);
    return out.isAfter(latest) ? Instant.ofEpochSecond((latest.getEpochSecond() / 300) * 300) : out;
  }

  /** Clock words for the reasons, in depot time. */
  static final class Wording {
    private Wording() {}

    static String hhmm(Instant t) {
      return java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(t.atZone(java.time.ZoneId.of("Asia/Colombo")));
    }
  }
}
