package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.DeterministicEstimator.DailyVolume;
import com.waypoint.dispatch.intelligence.domain.DeterministicEstimator.WeekVolume;
import com.waypoint.dispatch.intelligence.domain.ModelGate.Decision;
import com.waypoint.dispatch.intelligence.domain.ModelGate.Served;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.OrderFacts;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.Route;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.StopFacts;
import com.waypoint.dispatch.intelligence.domain.PlannedRoutes.TripFacts;
import com.waypoint.dispatch.intelligence.domain.SupplyPolicy.Inputs;
import com.waypoint.dispatch.intelligence.domain.SupplyPolicy.State;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The estimates Waypoint gives with no model, and the rules for when a model may answer instead. */
class IntelligenceDomainTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  // ---- service minutes ---------------------------------------------------------------

  @Test
  void anOutletWithEnoughHistoryGetsItsOwnMedian() {
    var e = DeterministicEstimator.serviceMinutes(
        List.of(d("12"), d("20"), d("14"), d("18"), d("16")), d("25"));
    assertEquals(d("16.00"), e.minutes());
    assertEquals("history", e.basis());
  }

  @Test
  void anEvenHistoryTakesTheMiddleTwo() {
    var e = DeterministicEstimator.serviceMinutes(
        List.of(d("10"), d("12"), d("14"), d("16"), d("18"), d("20")), d("25"));
    assertEquals(d("15.00"), e.minutes());
  }

  @Test
  void thinHistoryFallsBackToTheAllowance() {
    var e = DeterministicEstimator.serviceMinutes(List.of(d("12"), d("13"), d("14"), d("15")), d("25"));
    assertEquals(d("25.00"), e.minutes());
    assertEquals("allowance", e.basis(), "A-16: the allowance is a budget, used only without history");
  }

  // ---- lateness ---------------------------------------------------------------------------

  @Test
  void lateRiskRisesAsTheSlackRunsOut() {
    BigDecimal roomy = DeterministicEstimator.lateProbability(LocalTime.of(6, 0), LocalTime.of(8, 0), 0);
    BigDecimal tight = DeterministicEstimator.lateProbability(LocalTime.of(7, 50), LocalTime.of(8, 0), 0);
    BigDecimal after = DeterministicEstimator.lateProbability(LocalTime.of(8, 10), LocalTime.of(8, 0), 0);
    assertTrue(roomy.compareTo(d("0.01")) < 0, roomy.toString());
    assertTrue(tight.compareTo(roomy) > 0 && tight.compareTo(after) < 0);
    assertTrue(after.compareTo(d("0.95")) > 0, after.toString());
  }

  @Test
  void laterStopsCarryMoreDelay() {
    BigDecimal first = DeterministicEstimator.lateProbability(LocalTime.of(7, 40), LocalTime.of(8, 0), 0);
    BigDecimal fifth = DeterministicEstimator.lateProbability(LocalTime.of(7, 40), LocalTime.of(8, 0), 4);
    assertTrue(fifth.compareTo(first) > 0);
  }

  @Test
  void lateRiskIsNeverExactlyZeroOrOne() {
    assertEquals(d("0.0005"), DeterministicEstimator.lateProbability(LocalTime.of(2, 0), LocalTime.of(23, 0), 0));
    assertEquals(d("0.9995"), DeterministicEstimator.lateProbability(LocalTime.of(23, 0), LocalTime.of(2, 0), 0));
  }

  // ---- forecast -------------------------------------------------------------------------------

  @Test
  void aWeekIsTheMeanOfTheSameWeekdaysOverEightWeeks() {
    LocalDate origin = LocalDate.of(2026, 10, 5); // a Monday
    List<DailyVolume> history = new ArrayList<>();
    for (int w = 1; w <= 8; w++) {
      history.add(new DailyVolume(origin.minusWeeks(w), d("10"), d("4"))); // Mondays
      history.add(new DailyVolume(origin.minusWeeks(w).plusDays(2), d("6"), d("0"))); // Wednesdays
    }
    history.add(new DailyVolume(origin.minusWeeks(9), d("999"), d("999"))); // too old to count
    List<LocalDate> week = origin.datesUntil(origin.plusDays(6)).toList(); // Mon-Sat

    WeekVolume f = DeterministicEstimator.weekForecast(history, origin, week);

    assertEquals(d("16.0000"), f.totalM3());
    assertEquals(d("4.0000"), f.chilledM3());
  }

  @Test
  void aNewDepotIsForecastFromTheWeeksItHasNotAnEighthOfThem() {
    LocalDate origin = LocalDate.of(2026, 10, 5); // a Monday
    List<LocalDate> week = origin.datesUntil(origin.plusDays(6)).toList(); // Mon-Sat
    // One week of orders: last Monday 10 m3. Dividing by eight gave 1.25.
    List<DailyVolume> oneWeek = List.of(new DailyVolume(origin.minusWeeks(1), d("10"), d("4")));
    WeekVolume f = DeterministicEstimator.weekForecast(oneWeek, origin, week, origin.minusWeeks(1));
    assertEquals(d("10.0000"), f.totalM3(), "one week behind the depot, so divided by one");
    assertEquals(d("4.0000"), f.chilledM3());

    // Three weeks of orders, the same 10 m3 each Monday: a mean of 10, not 30 / 8.
    List<DailyVolume> threeWeeks = new ArrayList<>();
    for (int w = 1; w <= 3; w++) threeWeeks.add(new DailyVolume(origin.minusWeeks(w), d("10"), d("0")));
    assertEquals(d("10.0000"), DeterministicEstimator.weekForecast(threeWeeks, origin, week, origin.minusWeeks(3)).totalM3());

    // A depot with more than eight weeks behind it divides by eight, as before.
    List<DailyVolume> long_ = new ArrayList<>();
    for (int w = 1; w <= 8; w++) long_.add(new DailyVolume(origin.minusWeeks(w), d("10"), d("0")));
    assertEquals(d("10.0000"), DeterministicEstimator.weekForecast(long_, origin, week, origin.minusWeeks(20)).totalM3());

    // No orders at all: nothing, never a guess.
    assertEquals(d("0.0000"), DeterministicEstimator.weekForecast(List.of(), origin, week, null).totalM3());
  }

  @Test
  void aWeeksCapacityIsEachDaysOwnFleetAndItsFewestVehicles() {
    var reefer = new FleetCapacity.Vehicle(d("20"), true);
    var dry = new FleetCapacity.Vehicle(d("30"), false);
    // Monday every vehicle, Tuesday the reefer is in the workshop; two trips a day.
    FleetCapacity.Week w = FleetCapacity.weeklyByDay(List.of(
        new FleetCapacity.Day(List.of(reefer, dry), 2),
        new FleetCapacity.Day(List.of(dry), 2)));
    assertEquals(d("160.00"), w.fleetM3(), "(20 + 30) x 2 on Monday, 30 x 2 on Tuesday");
    assertEquals(d("40.00"), w.refrigeratedM3(), "the reefer only on Monday");
    assertEquals(1, w.vehicles(), "the fewest free on any day");
    assertEquals(0, w.refrigeratedVehicles(), "no reefer on Tuesday, so the week cannot count on one");
    FleetCapacity.Week none = FleetCapacity.weeklyByDay(List.of());
    assertEquals(0, none.vehicles());
    assertEquals(d("0.00"), none.fleetM3());
  }

  @Test
  void aClosedDayContributesNothing() {
    LocalDate origin = LocalDate.of(2026, 10, 5);
    List<DailyVolume> history = new ArrayList<>();
    for (int w = 1; w <= 8; w++) {
      history.add(new DailyVolume(origin.minusWeeks(w), d("10"), d("0")));
    }
    WeekVolume closedMonday =
        DeterministicEstimator.weekForecast(history, origin, origin.plusDays(1).datesUntil(origin.plusDays(6)).toList());
    assertEquals(d("0.0000"), closedMonday.totalM3(), "orders on a closed day are lost, not moved (A-38)");
  }

  // ---- model gate -------------------------------------------------------------------------------

  @Test
  void theModelIsUsedOnlyWhenTheServerHasTheActiveVersion() {
    Decision ok = ModelGate.decide("delivery_risk", true, Optional.of("m@1"), Served.loaded("m@1"));
    assertTrue(ok.useModel());
    assertTrue(ok.reason().isEmpty());
  }

  @Test
  void aServiceHoldingSeveralModelsAnswersWhenTheActiveOneIsAmongThem() {
    // Issue #12, R-ML-39: the image's model and the store's, each named by the request.
    assertTrue(ModelGate.decide("delivery_risk", true, Optional.of("ours@2"), Served.loaded(List.of("image@1", "ours@2")))
        .useModel());
    assertGate(ModelGate.decide("delivery_risk", true, Optional.of("ours@3"), Served.loaded(List.of("image@1", "ours@2"))),
        "has image@1, ours@2 but the active model is ours@3");
  }

  @Test
  void everyOtherCaseIsDegradedWithItsReason() {
    assertGate(ModelGate.decide("delivery_risk", false, Optional.of("m@1"), Served.loaded("m@1")), "not configured");
    assertGate(ModelGate.decide("delivery_risk", true, Optional.empty(), Served.loaded("m@1")), "no delivery_risk model");
    assertGate(ModelGate.decide("delivery_risk", true, Optional.of("m@1"), Served.unavailable("circuit open")),
        "unavailable: circuit open");
    assertGate(ModelGate.decide("delivery_risk", true, Optional.of("m@1"), Served.none()), "no delivery_risk model loaded");
    assertGate(ModelGate.decide("delivery_risk", true, Optional.of("m@1"), Served.loaded("m@2")),
        "has m@2 but the active model is m@1");
  }

  @Test
  void whereCompetitionModelsAreNotAllowedOneNeverAnswersButOthersDo() {
    // R-ML-09, roadmap MF-01: production sets ML_ALLOW_DATATHON=false.
    String datathon = "datathon-task2a@2026.1.1";
    assertGate(ModelGate.decide("demand_forecast", true, Optional.of(datathon), Served.loaded(datathon), false),
        "competition data are not allowed");
    assertTrue(ModelGate.decide("demand_forecast", true, Optional.of(datathon), Served.loaded(datathon), true).useModel(),
        "allowed on the judge build, CI and preview");
    String ours = "waypoint-forecast@2027.1.0";
    assertTrue(ModelGate.decide("demand_forecast", true, Optional.of(ours), Served.loaded(ours), false).useModel(),
        "a model trained on our own deliveries is not affected");
    assertTrue(ModelGate.competitionModel("datathon-task1-blend"));
    assertFalse(ModelGate.competitionModel("waypoint-forecast"));
  }

  private static void assertGate(Decision d, String reason) {
    assertFalse(d.useModel());
    assertTrue(d.reason().orElseThrow().contains(reason), d.reason().orElseThrow());
  }

  // ---- supply probability ---------------------------------------------------------------------

  @Test
  void aPlannedOrderIsAsLikelyAsItsOutletIsReliable() {
    var e = SupplyPolicy.estimate(new Inputs(State.OPEN, true, true, 1, 48, 0, 0));
    assertEquals("planned", e.basis());
    assertEquals(d("0.9600"), e.probability());
  }

  @Test
  void anOrderLeftOutOfAPublishedPlanIsNotSuppliedThatDay() {
    var e = SupplyPolicy.estimate(new Inputs(State.OPEN, true, false, 0, 0, 0, 0));
    assertEquals("deferred", e.basis());
    assertEquals(0, e.probability().signum());
  }

  @Test
  void anUnplannedOrderReadsTheRecentDeferralRate() {
    var e = SupplyPolicy.estimate(new Inputs(State.OPEN, false, false, 0, 0, 9, 89));
    assertEquals("deferral_rate", e.basis());
    assertEquals(d("0.9000"), e.probability());
  }

  @Test
  void noHistoryIsAnHonestHalf() {
    assertEquals(d("0.5000"), SupplyPolicy.estimate(new Inputs(State.OPEN, false, false, 0, 0, 0, 0)).probability());
  }

  @Test
  void cancelledAndDeliveredAreCertain() {
    assertEquals(0, SupplyPolicy.estimate(new Inputs(State.CANCELLED, true, true, 0, 0, 0, 0)).probability().signum());
    assertEquals(d("1.0000"), SupplyPolicy.estimate(new Inputs(State.DELIVERED, false, false, 0, 0, 0, 0)).probability());
  }

  // ---- fleet capacity -----------------------------------------------------------------------------

  @Test
  void aWeeksCapacityIsEveryVehicleTwiceADayOnEachOperatingDay() {
    var w = FleetCapacity.weekly(
        List.of(new FleetCapacity.Vehicle(d("10"), true), new FleetCapacity.Vehicle(d("30"), false)), List.of(2, 2, 2, 2, 2, 2));
    assertEquals(2, w.vehicles());
    assertEquals(1, w.refrigeratedVehicles());
    assertEquals(d("480.00"), w.fleetM3(), "(10 + 30) x 2 trips x 6 days");
    assertEquals(d("120.00"), w.refrigeratedM3(), "chilled goods ride only in reefers");
  }

  @Test
  void aClosedWeekCarriesNothing() {
    assertEquals(d("0.00"), FleetCapacity.weekly(List.of(new FleetCapacity.Vehicle(d("10"), true)), List.of()).fleetM3());
  }

  // ---- forecast schedule ----------------------------------------------------------------------

  private static final java.time.ZoneId COLOMBO = java.time.ZoneId.of("Asia/Colombo");

  private static java.time.Instant colombo(String local) {
    return java.time.LocalDateTime.parse(local).atZone(COLOMBO).toInstant();
  }

  private static Optional<ForecastSchedule.LastRun> ran(String local, boolean degraded) {
    return Optional.of(new ForecastSchedule.LastRun(colombo(local), degraded));
  }

  @Test
  void withNoRunTheForecastRunsAtTheNextHourDepotTime() {
    // Saturday 13:01:44 in Colombo is 07:31:44 UTC; the hour is Colombo's, so 14:00 there, 08:30 UTC.
    var now = colombo("2026-10-03T13:01:44");
    assertEquals(java.time.Instant.parse("2026-10-03T08:30:00Z"),
        ForecastSchedule.nextRun(now, Optional.empty(), false, COLOMBO));
    assertTrue(ForecastSchedule.due(now, Optional.empty(), false, COLOMBO));
  }

  @Test
  void aRunThisWeekWaitsForMondayMidnight() {
    var next = ForecastSchedule.nextRun(colombo("2026-10-03T14:05"), ran("2026-10-03T14:00", false), true, COLOMBO);
    assertEquals(colombo("2026-10-05T00:00"), next);
    assertFalse(ForecastSchedule.due(colombo("2026-10-04T23:00"), ran("2026-10-03T14:00", false), true, COLOMBO));
  }

  @Test
  void aFallbackRunIsRetriedSixHoursLaterOnlyOnceAModelIsActive() {
    var last = ran("2026-10-03T14:00", true);
    assertEquals(colombo("2026-10-03T20:00"), ForecastSchedule.nextRun(colombo("2026-10-03T14:05"), last, true, COLOMBO));
    assertEquals(colombo("2026-10-05T00:00"), ForecastSchedule.nextRun(colombo("2026-10-03T14:05"), last, false, COLOMBO),
        "with no model a retry would only write the fallback again");
  }

  // ---- routes ---------------------------------------------------------------------------------

  private static TripFacts trip(List<StopFacts> stops) {
    return new TripFacts(UUID.randomUUID(), "VEH001", "van", "reefer", "Fresh", "Kandy", "Kandy",
        LocalDate.of(2026, 10, 5), d("16"), d("6"), d("7.9"), d("2.8"), stops);
  }

  private static StopFacts stop(int seq, String outlet, String arrival, boolean withOrder) {
    return new StopFacts(seq, UUID.randomUUID(), outlet, LocalTime.parse(arrival), LocalTime.of(5, 0),
        LocalTime.of(8, 0),
        withOrder
            ? Optional.of(new OrderFacts(LocalDate.of(2026, 10, 4), false, "chilled", 10, d("80"), d("0.5")))
            : Optional.empty());
  }

  @Test
  void aTripBecomesLegsFromTheDepotThenOutletToOutlet() {
    Route r = PlannedRoutes.route(trip(List.of(stop(2, "OUT002", "05:38", true), stop(1, "OUT001", "05:16", true))))
        .orElseThrow();
    assertEquals(2, r.legs().size());
    var first = r.legs().get(0);
    var second = r.legs().get(1);
    assertEquals(0, first.seq());
    assertEquals("DEPOT", first.fromPoint());
    assertEquals("OUT001", first.outletId());
    assertEquals(LocalTime.of(5, 0), first.plannedDepart(), "arrival minus the outbound minutes");
    assertEquals(d("7.9"), first.distanceKm());
    assertEquals("OUT001", second.fromPoint());
    assertEquals(LocalTime.of(5, 32), second.plannedDepart(), "arrival minus the inter-stop minutes");
    assertEquals(d("2.8"), second.distanceKm());
  }

  @Test
  void aRouteWithAStopMissingItsOrderIsNotScoredByTheModel() {
    assertTrue(PlannedRoutes.route(trip(List.of(stop(1, "OUT001", "05:16", true), stop(2, "OUT002", "05:38", false))))
        .isEmpty());
  }
}
