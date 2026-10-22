package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.Playbooks.Situation;
import com.waypoint.dispatch.intelligence.domain.ProblemRoom.Ask;
import com.waypoint.dispatch.intelligence.domain.ProblemRoom.Kind;
import com.waypoint.dispatch.intelligence.domain.ProblemRoom.Outcome;
import com.waypoint.dispatch.intelligence.domain.ProblemRoom.Problem;
import com.waypoint.dispatch.intelligence.domain.ProblemRoom.Room;
import com.waypoint.dispatch.intelligence.domain.ProblemRoom.State;
import com.waypoint.dispatch.intelligence.domain.ProblemRoom.Stop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** R-ML-36: the problem room's rows and its one next step, rule by rule. */
class ProblemRoomTest {
  /** 05:30 depot time. */
  private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

  private static Instant at(int minutesFromNow) {
    return NOW.plus(Duration.ofMinutes(minutesFromNow));
  }

  private static Stop stop(String outlet, int seq, Outcome o, boolean mall, int arrives, int windowCloses) {
    return new Stop(UUID.nameUUIDFromBytes(outlet.getBytes()), outlet, seq, o, mall, false, at(windowCloses), at(arrives), 1);
  }

  private static Ask ask(Stop s, String kind, String state, String answer, int deadline) {
    return new Ask(UUID.randomUUID(), kind, s.deliveryId(), Optional.empty(), Optional.of(at(deadline + 10)), at(deadline),
        state, Optional.ofNullable(answer), NOW.minusSeconds(60));
  }

  /** Reported an hour ago, before every question in these tests was asked. */
  private static final Instant REPORTED = NOW.minus(Duration.ofHours(1));

  private static Room room(Situation s, List<Stop> stops, List<Ask> asks) {
    return ProblemRoom.of(new Problem(s, Optional.empty(), REPORTED), stops, asks, NOW, Optional.empty());
  }

  private static State state(Room r, String outlet) {
    return r.rows().stream().filter(x -> x.outletId().equals(outlet)).findFirst().orElseThrow().state();
  }

  // A closes soon, B closes late, C is a mall closing soon.
  private static final Stop A = stop("OUT001", 1, Outcome.WAITING, false, 20, 40);
  private static final Stop B = stop("OUT002", 2, Outcome.WAITING, false, 40, 240);
  private static final Stop C = stop("OUT003", 3, Outcome.WAITING, true, 60, 90);

  @Test
  void aBreakdownAsksOnlyTheStoresWhoseWindowTheExtraHourWouldMiss() {
    Room r = room(Situation.BREAKDOWN, List.of(A, B, C), List.of());
    assertEquals(Kind.ASK, r.next().kind());
    assertEquals(List.of("OUT001"), r.next().stops().stream().map(ProblemRoom.Target::outletId).toList());
    assertEquals(Optional.of(at(80)), r.next().stops().get(0).receiveAt(), "its arrival plus the hour, on a 5-minute mark");
    assertEquals(State.IN_WINDOW, state(r, "OUT002"));
    assertEquals(State.MALL_CANNOT_WAIT, state(r, "OUT003"), "a mall is never asked");
    assertTrue(r.next().endTripToo(), "a breakdown keeps its way out: the driver can't go on");
    assertEquals(Optional.of(Duration.ofHours(1)), r.next().extra());
  }

  @Test
  void aSmallerExtraTimeAsksFewerStores() {
    Stop d = stop("OUT004", 1, Outcome.WAITING, false, 20, 70);
    Room hour = ProblemRoom.of(new Problem(Situation.ROAD_BLOCKED, Optional.empty(), NOW), List.of(d), List.of(), NOW, Optional.of(Duration.ofHours(1)));
    Room half = ProblemRoom.of(new Problem(Situation.ROAD_BLOCKED, Optional.empty(), NOW), List.of(d), List.of(), NOW, Optional.of(Duration.ofMinutes(30)));
    assertEquals(Kind.ASK, hour.next().kind());
    assertEquals(Kind.DONE, half.next().kind(), "with 30 minutes it still makes its window");
    assertFalse(half.next().endTripToo(), "only a breakdown offers ending the trip beside");
  }

  @Test
  void runningLateAsksOnlyTheStoresAlreadyPastTheirWindowAtTheirOwnTime() {
    Stop late = stop("OUT005", 1, Outcome.WAITING, false, 50, 40);
    Room r = room(Situation.RUNNING_LATE, List.of(late, B), List.of());
    assertEquals(List.of("OUT005"), r.next().stops().stream().map(ProblemRoom.Target::outletId).toList());
    assertEquals(Optional.of(at(50)), r.next().stops().get(0).receiveAt());
    Room fine = room(Situation.RUNNING_LATE, List.of(B), List.of());
    assertEquals(Kind.DONE, fine.next().kind());
    assertTrue(fine.next().why().contains("can receive in time"), fine.next().why());
  }

  @Test
  void aStoreFoundClosedIsAskedAboutAQuarterOfAnHourFromNowAndNoOtherStoreIs() {
    Stop reached = stop("OUT006", 1, Outcome.REACHED, false, 0, 30);
    Room r = room(Situation.STORE_CLOSED, List.of(reached, B), List.of());
    assertEquals(List.of("OUT006"), r.next().stops().stream().map(ProblemRoom.Target::outletId).toList());
    assertEquals(Optional.of(at(15)), r.next().stops().get(0).receiveAt());
    assertEquals(State.IN_WINDOW, state(r, "OUT002"));
    Room named = ProblemRoom.of(new Problem(Situation.CANNOT_UNLOAD, Optional.of("OUT002"), NOW), List.of(reached, B), List.of(), NOW, Optional.empty());
    assertEquals("OUT002", named.next().stops().get(0).outletId(), "a report naming its store is about that store");
  }

  @Test
  void aStoreThatSaidNoIsSkippedAndOneThatSaidYesKeepsItsDelivery() {
    Room r = room(Situation.BREAKDOWN, List.of(A, B), List.of(ask(A, "receive_late", "answered", "no", 10)));
    assertEquals(Kind.SKIP, r.next().kind());
    assertEquals("OUT001", r.next().stops().get(0).outletId());
    assertTrue(r.next().why().startsWith("OUT001 can't receive at"), r.next().why());
    Room yes = room(Situation.BREAKDOWN, List.of(A, B), List.of(ask(A, "receive_late", "answered", "yes", 10)));
    assertEquals(State.YES, state(yes, "OUT001"));
    assertEquals(Kind.DONE, yes.next().kind());
  }

  @Test
  void theTripEndsOnlyWhenNoStoreStillWaitingCanReceiveToday() {
    Stop a = stop("OUT001", 1, Outcome.WAITING, false, 20, 40);
    Stop b = stop("OUT007", 2, Outcome.WAITING, false, 40, 60);
    Room allNo = room(Situation.BREAKDOWN, List.of(a, b),
        List.of(ask(a, "receive_late", "answered", "no", 10), ask(b, "receive_late", "answered", "no", 20)));
    assertEquals(Kind.END_TRIP, allNo.next().kind());
    assertFalse(allNo.next().endTripToo());
    Room oneYes = room(Situation.BREAKDOWN, List.of(a, b),
        List.of(ask(a, "receive_late", "answered", "no", 10), ask(b, "receive_late", "answered", "yes", 20)));
    assertEquals(Kind.SKIP, oneYes.next().kind(), "a store that said Yes keeps the trip going");
    Room alone = room(Situation.BREAKDOWN, List.of(a), List.of(ask(a, "receive_late", "answered", "no", 10)));
    assertEquals(Kind.SKIP, alone.next().kind(), "one store is skipped, not a trip ended");
  }

  @Test
  void aStoreThatDidNotAnswerByTheDeadlineIsCalledBeforeAnythingIsEnded() {
    Stop a = stop("OUT001", 1, Outcome.WAITING, false, 20, 40);
    Stop b = stop("OUT007", 2, Outcome.WAITING, false, 40, 60);
    Room r = room(Situation.BREAKDOWN, List.of(a, b),
        List.of(ask(a, "receive_late", "expired", null, -1), ask(b, "receive_late", "answered", "no", 20)));
    assertEquals(State.NO_ANSWER, state(r, "OUT001"));
    assertEquals(Kind.SKIP, r.next().kind(), "the No is decided first");
    Room onlyUnanswered = room(Situation.BREAKDOWN, List.of(a), List.of(ask(a, "receive_late", "expired", null, -1)));
    assertEquals(Kind.CALL, onlyUnanswered.next().kind());
  }

  @Test
  void aSkippedStoresGoodsInFrontOfTheNextStoresAskThatStoresDockThenTellTheDriverIfRefused() {
    Stop skipped = stop("OUT001", 1, Outcome.SKIPPED, false, 20, 40);
    Stop next = stop("OUT002", 2, Outcome.WAITING, false, 40, 240);
    Stop after = stop("OUT008", 3, Outcome.WAITING, false, 60, 240);
    Room ask = room(Situation.BREAKDOWN, List.of(skipped, next, after), List.of());
    assertEquals(Kind.ASK_DOCK, ask.next().kind());
    assertEquals("OUT002", ask.next().stops().get(0).outletId(), "only the store straight after");
    assertEquals("OUT001", ask.next().about().orElseThrow().outletId());
    Ask refused = new Ask(UUID.randomUUID(), "dock_help", next.deliveryId(), Optional.of(skipped.deliveryId()), Optional.empty(),
        at(20), "answered", Optional.of("no"), NOW);
    Room tell = room(Situation.BREAKDOWN, List.of(skipped, next, after), List.of(refused));
    assertEquals(Kind.TELL_DRIVER_DOCK, tell.next().kind());
    assertEquals(State.DOCK_NO, state(tell, "OUT002"));
    Stop done = stop("OUT002", 2, Outcome.DELIVERED, false, 40, 240);
    assertEquals(Kind.DONE, room(Situation.BREAKDOWN, List.of(skipped, done, after), List.of()).next().kind(),
        "once the next store is done, the goods were re-stowed there");
  }

  @Test
  void openQuestionsAreWaitedForWithTheirDeadline() {
    Room r = room(Situation.BREAKDOWN, List.of(A, B), List.of(ask(A, "receive_late", "sent", null, 12)));
    assertEquals(State.ASKED, state(r, "OUT001"));
    assertEquals(Kind.WAIT, r.next().kind());
    assertTrue(r.next().why().startsWith("1 answer due by"), r.next().why());
  }

  @Test
  void aWithdrawnQuestionCountsAsNotAskedAndTheLatestQuestionDecides() {
    Ask old = new Ask(UUID.randomUUID(), "receive_late", A.deliveryId(), Optional.empty(), Optional.empty(), at(5), "withdrawn",
        Optional.empty(), NOW.minusSeconds(600));
    assertEquals(Kind.ASK, room(Situation.BREAKDOWN, List.of(A), List.of(old)).next().kind());
    Ask no = new Ask(UUID.randomUUID(), "receive_late", A.deliveryId(), Optional.empty(), Optional.empty(), at(5), "answered",
        Optional.of("no"), NOW.minusSeconds(600));
    Ask yes = new Ask(UUID.randomUUID(), "receive_late", A.deliveryId(), Optional.empty(), Optional.empty(), at(10), "answered",
        Optional.of("yes"), NOW.minusSeconds(60));
    assertEquals(State.YES, state(room(Situation.BREAKDOWN, List.of(A), new ArrayList<>(List.of(no, yes))), "OUT001"));
  }

  @Test
  void aTripWithEveryStoreDoneIsSettled() {
    Stop done = stop("OUT001", 1, Outcome.DELIVERED, false, 20, 40);
    Room r = room(Situation.BREAKDOWN, List.of(done), List.of());
    assertEquals(Kind.DONE, r.next().kind());
    assertFalse(r.next().endTripToo());
  }

  @Test
  void aStoreIsNeverAskedToWaitMoreThanAnHourMore() {
    Room r = ProblemRoom.of(new Problem(Situation.BREAKDOWN, Optional.empty(), NOW), List.of(A), List.of(), NOW, Optional.of(Duration.ofHours(2)));
    assertEquals(Optional.of(at(80)), r.next().stops().get(0).receiveAt(), "two hours asked is an hour");
    assertEquals(List.of(Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1)), ProblemRoom.EXTRAS);
  }

  @Test
  void anAnswerToAQuestionAskedBeforeThisProblemDecidesNothingHere() {
    // The store said Yes to a late delivery; then the driver found it closed: it is asked again.
    Stop reached = stop("OUT001", 1, Outcome.REACHED, false, 0, 40);
    Ask earlier = new Ask(UUID.randomUUID(), "receive_late", reached.deliveryId(), Optional.empty(), Optional.empty(), at(5), "answered",
        Optional.of("yes"), NOW.minusSeconds(600));
    Room r = ProblemRoom.of(new Problem(Situation.STORE_CLOSED, Optional.of("OUT001"), NOW.minusSeconds(60)), List.of(reached), List.of(earlier), NOW, Optional.empty());
    assertEquals(Kind.ASK, r.next().kind(), r.toString());
  }

  @Test
  void aTimeAskedIsOnAFiveMinuteMarkAtLeastFiveMinutesAheadAndAtMostFourHours() {
    assertEquals(at(5), ProblemRoom.roundUp(NOW, NOW));
    assertEquals(at(25), ProblemRoom.roundUp(at(21), NOW));
    assertEquals(at(240), ProblemRoom.roundUp(at(600), NOW));
  }

  /** R-ML-45: a store's chat words after its open question that read as an answer are heard; nothing else is. */
  @Test
  void aStoresWordsAfterItsOpenQuestionAreHeardAsItsAnswer() {
    Ask open = ask(A, "receive_late", "sent", null, 30);
    Ask answered = ask(B, "receive_late", "answered", "yes", 30);
    Ask dock = ask(B, "dock_help", "sent", null, 30);
    Instant after = NOW.plusSeconds(30);
    var said = List.of(
        new ProblemRoom.Said(UUID.randomUUID(), "OUT001", "SAYS_YES", "ok", NOW.minusSeconds(120)),   // before the question
        new ProblemRoom.Said(UUID.randomUUID(), "OUT001", "THANKS", "thanks", after),                 // not an answer
        new ProblemRoom.Said(UUID.randomUUID(), "OUT001", "SAYS_NO", "ba, ada ganna ba", after),
        new ProblemRoom.Said(UUID.randomUUID(), "OUT002", "CAN_RECEIVE_LATER", "after 2", after));    // a dock is not a time
    List<ProblemRoom.Heard> heard = ProblemRoom.heard(List.of(open, answered, dock), List.of(A, B), said);
    assertEquals(1, heard.size(), heard.toString());
    assertEquals(open.requestId(), heard.get(0).requestId());
    assertEquals(ProblemRoom.Reads.NO, heard.get(0).reads());
    assertEquals("ba, ada ganna ba", heard.get(0).words());

    var later = List.of(new ProblemRoom.Said(UUID.randomUUID(), "OUT001", "CAN_RECEIVE_LATER", "after 2", after),
        new ProblemRoom.Said(UUID.randomUUID(), "OUT001", "SAYS_YES", "yes ok", after.plusSeconds(60)));
    assertEquals(ProblemRoom.Reads.YES, ProblemRoom.heard(List.of(open), List.of(A), later).get(0).reads(), "the newest words count");
  }
}
