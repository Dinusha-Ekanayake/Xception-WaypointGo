package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.Playbooks.Facts;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Situation;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Tally;
import com.waypoint.dispatch.intelligence.domain.Playbooks.Window;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Chance;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Outlook;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Signal;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Source;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Stop;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.StopAction;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.StopOutlook;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.Trip;
import com.waypoint.dispatch.intelligence.domain.TripOutlook.TripAction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** R-ML-34: one prediction per trip, from its signals and its stops' facts. */
class TripOutlookTest {
  private static final Instant AT = Instant.parse("2026-10-23T03:00:00Z");

  private static Stop stop(String outlet, int seq, boolean open, boolean reached, Window w, boolean mall, boolean deferred) {
    return new Stop(UUID.nameUUIDFromBytes(outlet.getBytes()), outlet, seq, new Facts(open, reached, w, mall, false, deferred, 2));
  }

  private static Signal signal(Situation s, String outlet) {
    return new Signal(UUID.randomUUID(), s, Optional.ofNullable(outlet), AT, Source.REPORT);
  }

  private static Outlook of(List<Signal> signals, List<Stop> stops) {
    return of(signals, stops, false);
  }

  private static Outlook of(List<Signal> signals, List<Stop> stops, boolean noneInWindow) {
    return TripOutlook.of(signals, new Trip((int) stops.stream().filter(s -> s.facts().stopOpen()).count(), noneInWindow), stops, s -> Tally.NONE);
  }

  private static double chance(List<? extends Chance<?>> list, Enum<?> a) {
    return list.stream().filter(c -> c.action() == a).mapToDouble(Chance::chance).sum();
  }

  private static double sum(List<? extends Chance<?>> list) {
    return list.stream().mapToDouble(Chance::chance).sum();
  }

  private static final Stop A = stop("OUT001", 1, true, false, Window.INSIDE, false, false);
  private static final Stop B = stop("OUT002", 2, true, false, Window.MISSED, false, false);
  private static final Stop DONE = stop("OUT000", 0, false, false, Window.INSIDE, false, false);

  @Test
  void aQuietTripHasNoOutlook() {
    assertEquals(Outlook.NONE, of(List.of(), List.of(A, B)));
  }

  @Test
  void aBreakdownTouchesEveryWaitingStopAndEndingTheTripSendsThemToTheNextDay() {
    Outlook o = of(List.of(signal(Situation.BREAKDOWN, null)), List.of(DONE, A, B));
    assertEquals(Optional.of(Situation.BREAKDOWN), o.tripSituation());
    assertEquals(List.of(TripAction.END_TRIP, TripAction.WAIT, TripAction.CARRY_ON), o.trip().stream().map(Chance::action).toList());
    assertEquals(0.5, chance(o.trip(), TripAction.END_TRIP), 1e-9);
    assertEquals(2, o.stops().size(), "the stop already done is not in it");
    StopOutlook inside = o.stops().get(0);
    StopOutlook missed = o.stops().get(1);
    // A stop goes to the next day if the trip ends, or if it does not and the stop is skipped anyway.
    assertEquals(0.5 + 0.5 * 0.1, chance(inside.chances(), StopAction.SKIP_NEXT_DAY), 0.011);
    assertTrue(chance(missed.chances(), StopAction.SKIP_NEXT_DAY) > chance(inside.chances(), StopAction.SKIP_NEXT_DAY));
    o.stops().forEach(s -> assertEquals(1.0, sum(s.chances()), 0.02));
    assertEquals(o.tripBecause(), inside.because());
  }

  @Test
  void aTripWhoseStopsWillAllMissTheirWindowsIsMoreLikelyToEnd() {
    double fresh = chance(of(List.of(signal(Situation.ROAD_BLOCKED, null)), List.of(A)).trip(), TripAction.END_TRIP);
    double hopeless = chance(of(List.of(signal(Situation.ROAD_BLOCKED, null)), List.of(A), true).trip(), TripAction.END_TRIP);
    assertTrue(hopeless > fresh, fresh + " then " + hopeless);
  }

  @Test
  void aStoreClosedIsAboutTheStopItNamesAndLeavesTheTripAlone() {
    Signal closed = signal(Situation.STORE_CLOSED, "OUT002");
    Outlook o = of(List.of(closed), List.of(A, B));
    assertTrue(o.trip().isEmpty());
    assertEquals(1, o.stops().size());
    StopOutlook b = o.stops().get(0);
    assertEquals("OUT002", b.outletId());
    assertEquals(Situation.STORE_CLOSED, b.situation());
    assertEquals(List.of(closed.messageId()), b.because());
    assertTrue(chance(b.chances(), StopAction.SKIP_NEXT_DAY) > 0);
    assertEquals(1.0, sum(b.chances()), 0.02);
  }

  @Test
  void aStoreClosedNamingNoStopIsAboutTheStopTheVanIsAt() {
    Stop at = stop("OUT002", 2, true, true, Window.INSIDE, false, false);
    Outlook o = of(List.of(signal(Situation.CANNOT_UNLOAD, null)), List.of(A, at));
    assertEquals("OUT002", o.stops().get(0).outletId());
    Outlook next = of(List.of(signal(Situation.CANNOT_UNLOAD, null)), List.of(A, B));
    assertEquals("OUT001", next.stops().get(0).outletId(), "else the next stop");
  }

  @Test
  void aSignalAboutAStopAlreadyDecidedAddsNothing() {
    assertEquals(Outlook.NONE, of(List.of(signal(Situation.STORE_CLOSED, "OUT000")), List.of(DONE, A)));
  }

  @Test
  void aMallPastItsWindowIsNeitherAskedNorDeliveredLate() {
    Stop mall = stop("OUT009", 1, true, false, Window.MISSED, true, false);
    StopOutlook o = of(List.of(signal(Situation.RUNNING_LATE, null)), List.of(mall)).stops().get(0);
    assertEquals(0.0, chance(o.chances(), StopAction.ASK_RECEIVE_LATER));
    assertEquals(0.0, chance(o.chances(), StopAction.DELIVER));
    assertEquals(1.0, chance(o.chances(), StopAction.SKIP_NEXT_DAY), 0.01);
  }

  @Test
  void aStoreDeferredBeforeIsWarnedBeforeASecondSkip() {
    Stop again = stop("OUT003", 1, true, false, Window.MISSED, false, true);
    StopOutlook o = of(List.of(signal(Situation.RUNNING_LATE, null)), List.of(again)).stops().get(0);
    Chance<StopAction> skip = o.chances().stream().filter(c -> c.action() == StopAction.SKIP_NEXT_DAY).findFirst().orElseThrow();
    assertTrue(skip.warning().orElseThrow().contains("skipped twice"));
  }

  @Test
  void aTripWithNoStopsLeftHasNothingToDecide() {
    assertEquals(Outlook.NONE, of(List.of(signal(Situation.BREAKDOWN, null)), List.of(DONE)));
  }

  @Test
  void theMostSeriousTripSituationLeads() {
    Outlook o = of(List.of(signal(Situation.RUNNING_LATE, null), signal(Situation.BREAKDOWN, null)), List.of(A));
    assertEquals(Optional.of(Situation.BREAKDOWN), o.tripSituation());
    assertEquals(2, o.tripBecause().size());
  }
}
