package com.waypoint.dispatch.planning.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ReloadMeasureTest {

  @Test
  void theMedianAndTheThreeInFourOfADepotsReloads() {
    var measure = ReloadMeasure.of("PLG", List.of(30L, 22L, 25L, 41L, 28L, 35L, 26L, 33L, 24L, 60L)).orElseThrow();
    assertEquals(28, measure.median());
    assertEquals(35, measure.p75());
    assertEquals(10, measure.samples());
  }

  @Test
  void fewerThanTenReloadsSayNothing() {
    assertTrue(ReloadMeasure.of("PLG", LongStream.rangeClosed(1, 9).boxed().toList()).isEmpty());
  }

  @Test
  void aReloadOfNoTimeIsNotASample() {
    var minutes = new java.util.ArrayList<>(LongStream.rangeClosed(20, 28).boxed().toList());
    minutes.add(0L);
    assertTrue(ReloadMeasure.of("PLG", minutes).isEmpty(), "nine real reloads and a zero are not ten");
  }

  @Test
  void eachDepotIsMeasuredOnItsOwn() {
    var measures = ReloadMeasure.byDepot(Map.of(
        "PLG", LongStream.rangeClosed(20, 31).boxed().toList(),
        "KDY", List.of(15L, 16L)));
    assertEquals(1, measures.size());
    assertEquals("PLG", measures.get(0).depotCode());
  }
}
