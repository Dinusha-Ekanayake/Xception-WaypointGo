package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** R-ML-33: the question Jev is asked in shadow about a trip-chat report. */
class ReportTriageTest {

  @Test
  void everySituationOfThePlaybooksIsAnOptionAndAPickedTypeIsItsLabel() {
    for (String type : List.of("failed_delivery", "store_closed", "cannot_unload", "vehicle_fault", "road_disruption",
        "late", "damaged_goods", "receipt_dispute", "stock_discrepancy", "loading_shortfall")) {
      String situation = Playbooks.ofReport(type).orElseThrow().name();
      assertEquals(true, ReportTriage.OPTIONS.containsKey(situation), situation);
    }
    assertEquals("STORE_CLOSED", ReportTriage.label("failed_delivery"));
    assertEquals("BREAKDOWN", ReportTriage.label("vehicle_fault"));
    assertEquals("OTHER", ReportTriage.label("other"));
    assertEquals("OTHER", ReportTriage.label(null));
  }

  @Test
  void theOptionsComeInAnOrderFixedPerReport() {
    assertEquals(List.copyOf(ReportTriage.shuffled(9).keySet()), List.copyOf(ReportTriage.shuffled(9).keySet()));
    assertEquals(ReportTriage.OPTIONS.keySet(), ReportTriage.shuffled(3).keySet());
  }
}
