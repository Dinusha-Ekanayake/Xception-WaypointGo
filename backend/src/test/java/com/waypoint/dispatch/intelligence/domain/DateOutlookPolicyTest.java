package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.waypoint.dispatch.intelligence.domain.DateOutlookPolicy.Inputs;
import com.waypoint.dispatch.intelligence.domain.DateOutlookPolicy.Status;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #224, R-ML-07: how likely a delivery day is to be kept. */
class DateOutlookPolicyTest {
  static BigDecimal m3(String v) {
    return new BigDecimal(v);
  }

  static Inputs day(String booked, String forecast, String room) {
    return new Inputs(true, m3(booked), BigDecimal.ZERO, Optional.ofNullable(forecast).map(BigDecimal::new),
        Optional.of(BigDecimal.ZERO), m3(room), m3("40"), false);
  }

  @Test
  void theDayIsOnTrackBelowEightyPercentBusyFromItAndAtRiskPastTheRoom() {
    assertEquals(Status.ON_TRACK, DateOutlookPolicy.assess(day("10", "79", "100")).status());
    assertEquals(Status.BUSY, DateOutlookPolicy.assess(day("10", "80", "100")).status());
    assertEquals(Status.BUSY, DateOutlookPolicy.assess(day("10", "100", "100")).status(), "full is busy, not over");
    assertEquals(Status.AT_RISK, DateOutlookPolicy.assess(day("10", "101", "100")).status());
  }

  @Test
  void whatIsBookedCountsWhenItIsMoreThanTheForecast() {
    var o = DateOutlookPolicy.assess(day("90", "50", "100"));
    assertEquals(Status.BUSY, o.status());
    assertEquals(m3("0.9000"), o.load().orElseThrow());
  }

  @Test
  void withNoForecastALightDayIsTooEarlyToSayButAHeavyOneIsStillCalled() {
    assertEquals(Status.TOO_EARLY, DateOutlookPolicy.assess(day("5", null, "100")).status());
    assertEquals(Status.AT_RISK, DateOutlookPolicy.assess(day("120", null, "100")).status(),
        "booked alone past the room is at risk whatever the forecast");
  }

  @Test
  void aVehicleInTheWorkshopShrinksTheRoom() {
    assertEquals(Status.ON_TRACK, DateOutlookPolicy.assess(day("0", "60", "100")).status());
    assertEquals(Status.BUSY, DateOutlookPolicy.assess(day("0", "60", "70")).status());
    var none = DateOutlookPolicy.assess(day("0", "60", "0"));
    assertEquals(Status.AT_RISK, none.status());
    assertEquals("No vehicle is available that day", none.reason());
  }

  @Test
  void chilledGoodsAreSetAgainstReefersOnlyAndOnlyForAStoreThatSendsThem() {
    Inputs cold = new Inputs(true, m3("30"), m3("30"), Optional.of(m3("40")), Optional.of(m3("35")),
        m3("200"), m3("40"), true);
    var o = DateOutlookPolicy.assess(cold);
    assertEquals(Status.BUSY, o.status(), "35 of 40 reefer m3, though the whole fleet is a fifth full");
    assertTrue(o.reason().contains("refrigerated"));

    Inputs ambientStore = new Inputs(true, m3("30"), m3("30"), Optional.of(m3("40")), Optional.of(m3("35")),
        m3("200"), m3("40"), false);
    assertEquals(Status.ON_TRACK, DateOutlookPolicy.assess(ambientStore).status());
  }

  @Test
  void aDayTheNetworkDoesNotRunIsClosed() {
    var o = DateOutlookPolicy.assess(new Inputs(false, BigDecimal.ZERO, BigDecimal.ZERO, Optional.empty(),
        Optional.empty(), BigDecimal.ZERO, BigDecimal.ZERO, false));
    assertEquals(Status.CLOSED, o.status());
    assertTrue(o.load().isEmpty());
  }
}
