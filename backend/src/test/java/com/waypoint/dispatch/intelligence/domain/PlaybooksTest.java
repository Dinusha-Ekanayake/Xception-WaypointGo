package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.Playbooks.Action;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Facts;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Situation;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Suggestion;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Window;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The situation catalogue and its rules, with no database and no clock (issue #269). */
class PlaybooksTest {
  private static Facts at(boolean open, Window window, boolean mall, boolean chilled, boolean deferredBefore, int left) {
    return new Facts(open, true, window, mall, chilled, deferredBefore, left);
  }

  private static List<Action> actions(List<Suggestion> s) {
    return s.stream().map(Suggestion::action).toList();
  }

  @Test
  void aClosedStoreAtTheDoorOffersCallingFirstThenSkippingAndAtMostThree() {
    List<Suggestion> s = Playbooks.suggest(Situation.STORE_CLOSED, at(true, Window.INSIDE, false, false, false, 2));
    assertEquals(List.of(Action.CALL_STORE, Action.SKIP_NEXT_DAY, Action.WAIT), actions(s));
    assertEquals(1.0, s.stream().mapToDouble(Suggestion::chance).sum(), 0.02, "chances sum to one over what is offered");
  }

  @Test
  void aStopAlreadyDoneTakesNoSkipNoWaitAndNoQuestion() {
    List<Action> a = actions(Playbooks.suggest(Situation.STORE_CLOSED, at(false, Window.INSIDE, false, false, false, 1)));
    assertFalse(a.contains(Action.SKIP_NEXT_DAY));
    assertFalse(a.contains(Action.WAIT));
    assertEquals(List.of(Action.CALL_STORE), a);
  }

  @Test
  void aMallIsNeverAskedAndNeverWaitedAtPastItsWindow() {
    List<Action> late = actions(Playbooks.suggest(Situation.RUNNING_LATE, at(true, Window.MISSED, true, false, false, 2)));
    assertFalse(late.contains(Action.ASK_RECEIVE_LATER));
    List<Action> closed = actions(Playbooks.suggest(Situation.STORE_CLOSED, at(true, Window.MISSED, true, false, false, 2)));
    assertFalse(closed.contains(Action.WAIT));
    assertEquals(Action.SKIP_NEXT_DAY, closed.get(0), "past the window the day is lost for the store");
  }

  @Test
  void aStoreDeferredBeforeIsWarnedOfASecondSkip() {
    Suggestion skip = Playbooks.suggest(Situation.STORE_CLOSED, at(true, Window.INSIDE, false, false, true, 2)).stream()
        .filter(x -> x.action() == Action.SKIP_NEXT_DAY).findFirst().orElseThrow();
    assertTrue(skip.warning().orElseThrow().contains("skipped twice"));
  }

  @Test
  void chilledGoodsPastTheWindowWarnAgainstWaiting() {
    Optional<Suggestion> wait = Playbooks.suggest(Situation.BREAKDOWN, at(true, Window.MISSED, false, true, false, 2)).stream()
        .filter(x -> x.action() == Action.WAIT).findFirst();
    assertTrue(wait.orElseThrow().warning().orElseThrow().contains("cold chain"));
  }

  @Test
  void endTripNeedsAStopLeft() {
    assertFalse(actions(Playbooks.suggest(Situation.BREAKDOWN, at(true, Window.INSIDE, false, false, false, 0))).contains(Action.END_TRIP));
    assertEquals(Action.END_TRIP, Playbooks.suggest(Situation.BREAKDOWN, at(true, Window.INSIDE, false, false, false, 3)).get(0).action());
  }

  @Test
  void reportsNameTheirSituationAndAWindowIsJudgedAgainstItsClose() {
    assertEquals(Optional.of(Situation.BREAKDOWN), Playbooks.ofReport("vehicle_fault"));
    assertEquals(Optional.of(Situation.GOODS_PROBLEM), Playbooks.ofReport("loading_shortfall"));
    assertEquals(Optional.empty(), Playbooks.ofReport("other"));
    Instant close = Instant.parse("2027-03-01T03:00:00Z");
    assertEquals(Window.CLOSING, Window.at(Optional.of(close), close.minusSeconds(20 * 60)));
    assertEquals(Window.INSIDE, Window.at(Optional.of(close), close.minusSeconds(90 * 60)));
    assertEquals(Window.MISSED, Window.at(Optional.of(close), close.plusSeconds(60)));
    assertEquals(Window.UNKNOWN, Window.at(Optional.empty(), close));
  }

  @Test
  void everySituationHasStepsAndEveryBlankIsOneTheTripCanFill() {
    // Moved from the frontend's dispatcher-playbooks.test.ts with the catalogue.
    java.util.Set<String> known = java.util.Set.of("vehicle", "store", "expected", "window close");
    for (Playbooks.Playbook p : Playbooks.all()) {
      assertFalse(p.steps().isEmpty(), p.situation() + " has no steps");
      p.message().ifPresent(m -> java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(m.template()).results()
          .forEach(r -> assertTrue(known.contains(r.group(1)), p.situation() + " uses {" + r.group(1) + "}")));
    }
  }

  @Test
  void belowTwentyDecidedCasesTheRulePriorsStand() {
    Facts f = at(true, Window.INSIDE, false, false, false, 2);
    Playbooks.Tally few = new Playbooks.Tally(
        java.util.Map.of(Action.SKIP_NEXT_DAY, 19), java.util.Map.of(Action.SKIP_NEXT_DAY, 19), 19);
    assertFalse(Playbooks.learned(few));
    assertEquals(Playbooks.suggest(Situation.STORE_CLOSED, f), Playbooks.suggest(Situation.STORE_CLOSED, f, few));
  }

  @Test
  void onceLearnedWhatDispatchersTakeHereComesFirstAndNothingFallsToZero() {
    Facts f = at(true, Window.INSIDE, false, false, false, 2);
    Playbooks.Tally learned = new Playbooks.Tally(
        java.util.Map.of(Action.CALL_STORE, 30, Action.SKIP_NEXT_DAY, 30, Action.WAIT, 30),
        java.util.Map.of(Action.SKIP_NEXT_DAY, 24, Action.CALL_STORE, 3), 27);
    List<Suggestion> s = Playbooks.suggest(Situation.STORE_CLOSED, f, learned);
    assertEquals(Action.SKIP_NEXT_DAY, s.get(0).action(), "taken 24 of 30 times here");
    assertTrue(s.stream().allMatch(x -> x.chance() > 0), "a decision never taken keeps a little of its prior");
  }

  @Test
  void theFiveNeedsYouPlaybooksKeepTheirSteps() {
    assertEquals(3, Playbooks.playbook(Situation.FAILED).steps().size());
    assertTrue(Playbooks.playbook(Situation.ISSUE).message().isEmpty());
    assertEquals(11, Playbooks.all().size());
  }
}
