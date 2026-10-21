package com.waypoint.dispatch.intelligence.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** docs/issues/watch-review/PLAN.md: the model's order is used only as an exact reordering of what was sent. */
class WatchReviewTest {
  private final List<WatchReview.Item> sent = WatchReview.items(List.of(
      new WatchReview.Item(null, "FAILED_STOP", "CRITICAL", null, "OUT010", "VEH030"),
      new WatchReview.Item(null, "WINDOW_AT_RISK", "HIGH", 48, "OUT063", "VEH020")));

  @Test
  void itemsGoUnderShortReferencesInTheRulesOrder() {
    assertEquals(List.of("A1", "A2"), sent.stream().map(WatchReview.Item::ref).toList());
    assertEquals(2, ((List<?>) WatchReview.facts(sent).get("items")).size());
  }

  @Test
  void anExactReorderingIsUsedAndSaysWhetherItAgreesOnTheFirst() {
    assertTrue(WatchReview.judge(sent, List.of("A1", "A2")).orElseThrow().agreesFirst());
    assertFalse(WatchReview.judge(sent, List.of("A2", "A1")).orElseThrow().agreesFirst());
  }

  @Test
  void anOrderWithAnItemNeverSentMissingOrTwiceIsRefused() {
    assertTrue(WatchReview.judge(sent, List.of("A1", "A9")).isEmpty());
    assertTrue(WatchReview.judge(sent, List.of("A1")).isEmpty());
    assertTrue(WatchReview.judge(sent, List.of("A1", "A1")).isEmpty());
    assertTrue(WatchReview.judge(sent, null).isEmpty());
  }

  @Test
  void twelveItemsGoOutWholeAndACodeHoldingAPhoneIsMasked() throws Exception {
    java.util.List<WatchReview.Item> raised = new java.util.ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      raised.add(new WatchReview.Item(null, "WINDOW_AT_RISK", "MEDIUM", 30 + i, "OUT0" + (10 + i), "VEH0" + (10 + i)));
    }
    raised.set(0, new WatchReview.Item(null, "FAILED_STOP", "CRITICAL", null, "0771234567", "VEH011"));
    String facts = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
        WatchReview.facts(WatchReview.items(raised)));
    // A facts string cut at 300 characters was no longer JSON, so every busy depot was refused.
    com.fasterxml.jackson.databind.JsonNode back = new com.fasterxml.jackson.databind.ObjectMapper().readTree(facts);
    org.junit.jupiter.api.Assertions.assertEquals(12, back.path("items").size());
    org.junit.jupiter.api.Assertions.assertEquals("A12", back.path("items").path(11).path("ref").asText());
    org.junit.jupiter.api.Assertions.assertFalse(facts.contains("0771234567"), facts);
  }
}
